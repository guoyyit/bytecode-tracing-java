# bytecode-tracing-java

本地字节码插桩与方法级调用追踪服务。在不修改业务源码、不影响业务结果的前提下，
对运行中的类做方法级改写，采集**方法进入、正常返回、异常返回、耗时与调用深度**，
并以低开销、并发安全的方式对外提供有界聚合结果。

## 构建与测试

```bash
mvn test          # 运行全部测试（需要 JDK 17+）
mvn spring-boot:run
```

运行期 attach 需要 `-Djdk.attach.allowAttachSelf=true`（surefire 已配置）；
生产环境建议改用 `-javaagent` 或外部 attach。

## 体系结构

```
instrumented method                tracing runtime
┌─────────────────────┐            ┌──────────────────────────────┐
│ token = enter(id)   │──────────▶ │ TraceHooks (static, 每线程帧栈)│
│ try {               │            │   │ drain                     │
│   ...body...        │            │   ▼                           │
│   exit(token)       │──────────▶ │ TraceAggregator               │
│ } catch {           │            │   ├─ 有界方法聚合 (读写锁)      │
│   error(token)      │──────────▶ │   ├─ 有界线程聚合              │
│   throw             │            │   └─ 定长事件环形缓冲           │
└─────────────────────┘            └──────────────────────────────┘
```

- `instrument/MethodInstrumentor`：ASM 两遍改写（先按 `TraceFilter` 选定方法，再注入回调），
  离线输入/输出均为 class 字节数组，可单测。
- `instrument/RuntimeInstrumentationService`：基于 `java.lang.instrument` 的
  retransform，对**已加载类**追加/撤销插桩。
- `tracing/TraceHooks`：被插桩字节码直接调用的静态回调，维护每线程帧栈与深度令牌。
- `tracing/TraceAggregator`：有界并发聚合，提供快照、增量与条件查询。
- `api/TraceController`：REST 端点（见下）。

## 插桩范围与语义

**改写内容**：对每个被过滤器选中的方法——

1. 方法入口（构造器则在 `super()` 调用之后）插入
   `int token = TraceHooks.enter(methodId)`，token 存入一个**新增局部变量槽**；
2. 每个 `RETURN/xRETURN` 前插入 `TraceHooks.exit(token)`；
3. 方法体整体包进一个 catch-all 异常处理器：`TraceHooks.error(token)` 后原样 rethrow。

由此保证：

- **每个调用帧恰好计一次**：正常路径只有一个 return 会执行；异常路径由 catch-all
  记录一次并沿调用栈逐层传播（每帧各记一次）；`finally`、多 return、try-with-resources
  均不会重复计数；递归按调用深度分别归属（深度直方图 + 最大深度）。
- **行为保留**：行号表、局部变量表（LocalVariableTable）、泛型签名原样保留；
  栈映射帧（StackMapTable）整体重算，保证可验证、可加载。
- **不插桩**：`<clinit>`、abstract/native 方法、synthetic/bridge 方法。
- **全有或全无**：畸形或不支持的 class 文件抛出 `InstrumentationException`，
  不产生半改写结果；没有方法命中过滤器时**原样返回输入字节**。

**过滤器**（`TraceFilterSpec`）：方法可见性、方法名正则、方法/类注解，AND 语义。
调用深度过滤（`maxDepth`）在运行期判定。采样率（`samplingRate`，0..1）与开关
（`enabled`）运行期可调，归零/关闭时业务结果完全不变。

## 开销模型

| 场景 | 开销 |
|---|---|
| 未命中过滤器的方法 | **零**：字节码不含任何回调指令（有结构性测试验证） |
| 命中但 `enabled=false` 或采样率=0 | 每次调用约 2 次静态调用 + 每线程帧栈 push/pop（数 ns），不产生事件 |
| 命中且被采样 | 上述 + `nanoTime` + 一次有界聚合写入（读锁，无分配风暴：仅完成事件入环） |

内存上界（与调用次数无关）：

- 方法聚合：`maxMethods`（默认 10_000）个定长条目（计数/错误/总耗时/极值/65 桶深度直方图）；
- 线程聚合：≤ 4096 个定长条目；
- 事件环形缓冲：`eventBufferCapacity`（默认 16384，2 的幂）；
- 每线程帧栈：随真实调用深度增长，硬上限 16_384 帧。

容量耗尽时事件计为 `droppedEvents`（可观测），绝不无限增长。

## 并发语义

- **记录**：`recordEvent` 持读锁，任意多线程并行；序列号在读锁内分配。
- **快照**：`snapshot()` 持写锁，因此快照恰好包含序列号 `< S` 的全部事件——
  任一时刻都是自洽的某一时间点视图（有并发测试验证方法聚合与线程聚合恒等）。
- **增量**：`delta(since)` 返回 `(since, now]` 的事件；若区间最旧部分已被环形缓冲
  逐出，返回 `gap=true`（有界内存优先，已确认的完成事件在缓冲容量内不丢失）。
- **运行期变更**：`retransformClasses` 以类为单位原子生效；并发业务调用要么走旧
  版本要么走新版本，单次调用绝不混合两种状态。撤销时还原**原始字节**，
  不残留任何回调引用（有测试：撤销+reset 后再调用零事件）。
- **SOE 健壮性**：exit/error 以**绝对深度令牌**重置每线程帧栈，即使在
  `StackOverflowError` 展开过程中个别回调无法执行，深度计数也会由后续较浅帧自动
  校正归零（有测试）。

## REST API

| 端点 | 说明 |
|---|---|
| `GET /api/trace/snapshot?method=` | 一致时间点快照，可按方法名片段过滤 |
| `GET /api/trace/delta?since=` | 增量事件（含 `gap` 标志） |
| `GET /api/trace/events?methodId=&threadId=&from=&to=&limit=` | 按方法/线程/时间窗查询近期事件 |
| `GET/POST /api/trace/config` | 查看/更新 `enabled`、`samplingRate`、`maxDepth` |
| `POST /api/trace/instrument` | 对已加载类追加插桩（className + 过滤条件） |
| `DELETE /api/trace/instrument?className=` | 撤销插桩，还原原始字节 |
| `POST /api/trace/reset` | 清空聚合 |
| `GET /api/trace/methods` | 已注册方法列表 |

## 已知限制

- 运行期插桩要求目标类能访问应用类加载器（不支持 bootstrap 类）。
- 帧重算需要能解析类型层级：无法解析时抛出明确的 `InstrumentationException`。
- 深度直方图 65 桶封顶，更深帧并入末桶；每线程跟踪深度硬上限 16_384。
- 增量窗口受环形缓冲容量限制，超出部分以 `gap` 标志显式告知。
