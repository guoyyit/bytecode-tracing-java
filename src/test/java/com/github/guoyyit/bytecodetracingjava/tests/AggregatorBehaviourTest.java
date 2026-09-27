package com.github.guoyyit.bytecodetracingjava.tests;

import com.github.guoyyit.bytecodetracingjava.support.TracingTestHarness;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodStats;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceAggregator;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceEvent;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Aggregation, sampling, depth filtering, snapshot and delta semantics. */
class AggregatorBehaviourTest {

    private static final String FIXTURES = "com.github.guoyyit.bytecodetracingjava.fixtures.";

    private TracingTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TracingTestHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private Class<?> load(String fixture, ClassLoader loader) {
        try {
            return loader.loadClass(fixture);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object invokeStatic(Class<?> clazz, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = clazz.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test
    void zeroSamplingRecordsNothingButKeepsResults() throws Exception {
        harness.config().setSamplingRate(0.0);
        Class<?> fixture = load(FIXTURES + "RecursiveFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "RecursiveFixture"));

        long result = (long) invokeStatic(fixture, "factorial", new Class<?>[]{int.class}, 10);
        assertEquals(3628800L, result, "business result must be unchanged");
        assertEquals(0, harness.snapshot().totalCount(), "no events at zero sampling");
    }

    @Test
    void disabledTracingRecordsNothing() throws Exception {
        harness.config().setEnabled(false);
        Class<?> fixture = load(FIXTURES + "RecursiveFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "RecursiveFixture"));

        invokeStatic(fixture, "factorial", new Class<?>[]{int.class}, 10);
        assertEquals(0, harness.snapshot().totalCount());
    }

    @Test
    void halfSamplingRecordsRoughlyHalf() throws Exception {
        harness.config().setSamplingRate(0.5);
        Class<?> fixture = load(FIXTURES + "HighVolumeFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "HighVolumeFixture"));

        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 200_000);
        long count = harness.countOf("tiny");
        assertTrue(count > 80_000 && count < 120_000,
                "expected ~50% of 200k calls, got " + count);
    }

    @Test
    void maxDepthSkipsDeeperFrames() throws Exception {
        harness.config().setMaxDepth(3);
        Class<?> fixture = load(FIXTURES + "RecursiveFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "RecursiveFixture"));

        invokeStatic(fixture, "factorial", new Class<?>[]{int.class}, 10);
        MethodStats stats = harness.statsFor("factorial");
        assertNotNull(stats);
        assertEquals(3, stats.count(), "only frames at depth 1..3 are traced");
        assertEquals(3, stats.maxDepthObserved());
    }

    @Test
    void snapshotIsConsistentUnderConcurrentLoad() throws Exception {
        Class<?> fixture = load(FIXTURES + "ConcurrencyFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "ConcurrencyFixture"));
        Method work = fixture.getDeclaredMethod("work", long.class, int.class);

        int threads = 8;
        int callsPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long seed = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        work.invoke(null, seed, 50);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();

        // Take snapshots while the load is running; each must be self-consistent.
        long expectedTotal = (long) threads * callsPerThread;
        for (int i = 0; i < 50; i++) {
            TraceSnapshot snapshot = harness.snapshot();
            long sum = snapshot.methodStats().values().stream()
                    .mapToLong(MethodStats::count).sum();
            assertEquals(snapshot.totalCount(), sum);
            long threadSum = snapshot.threadStats().values().stream()
                    .mapToLong(TraceSnapshot.ThreadStatsView::count).sum();
            assertEquals(sum, threadSum + snapshot.droppedEvents(),
                    "method and thread aggregates must account for the same events");
            assertTrue(sum <= expectedTotal);
        }
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        TraceSnapshot finished = harness.snapshot();
        assertEquals(expectedTotal, finished.totalCount());
        assertEquals(expectedTotal, finished.threadStats().values().stream()
                .mapToLong(TraceSnapshot.ThreadStatsView::count).sum());
        assertEquals(threads, finished.threadStats().size());
    }

    @Test
    void deltaReadsReturnEventsInOrderAndFlagGaps() throws Exception {
        Class<?> fixture = load(FIXTURES + "HighVolumeFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "HighVolumeFixture"));

        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 100);
        long seqAfterFirst = harness.aggregator().currentSequence();
        TraceAggregator.Delta first = harness.aggregator().delta(-1);
        assertFalse(first.gap());
        assertEquals(101, first.events().size(), "loop + 100 tiny calls");
        assertEquals(seqAfterFirst, first.toSequence());

        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 50);
        TraceAggregator.Delta second = harness.aggregator().delta(seqAfterFirst - 1);
        assertFalse(second.gap());
        assertEquals(51, second.events().size());
        long previous = -1;
        for (TraceEvent event : second.events()) {
            assertTrue(event.sequence() > previous, "events must be in sequence order");
            previous = event.sequence();
        }

        // Overflow the ring buffer (capacity 16384) to force a gap.
        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 100_000);
        TraceAggregator.Delta gapped = harness.aggregator().delta(-1);
        assertTrue(gapped.gap(), "oldest events were evicted from the bounded buffer");
        assertEquals(harness.config().getEventBufferCapacity(), gapped.events().size());
    }

    @Test
    void queryFiltersByMethodThreadAndTimeWindow() throws Exception {
        Class<?> fixture = load(FIXTURES + "RecursiveFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "RecursiveFixture"));
        long before = System.currentTimeMillis();
        invokeStatic(fixture, "factorial", new Class<?>[]{int.class}, 4);
        Thread.sleep(5);
        long after = System.currentTimeMillis();

        int factorialId = harness.registry().all().stream()
                .filter(d -> d.methodName().equals("factorial")).findFirst().orElseThrow().id();
        List<TraceEvent> byMethod = harness.aggregator()
                .query(factorialId, null, 0, Long.MAX_VALUE, 100);
        assertEquals(4, byMethod.size());

        List<TraceEvent> byThread = harness.aggregator()
                .query(null, Thread.currentThread().getId(), 0, Long.MAX_VALUE, 100);
        assertEquals(4, byThread.size());

        List<TraceEvent> byWindow = harness.aggregator()
                .query(null, null, before - 1, after + 1, 100);
        assertEquals(4, byWindow.size());

        List<TraceEvent> emptyWindow = harness.aggregator()
                .query(null, null, 0, before - 1000, 100);
        assertTrue(emptyWindow.isEmpty());

        List<TraceEvent> limited = harness.aggregator()
                .query(null, null, 0, Long.MAX_VALUE, 2);
        assertEquals(2, limited.size());
    }

    @Test
    void samplingRateChangeTakesEffectAtRuntime() throws Exception {
        Class<?> fixture = load(FIXTURES + "HighVolumeFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "HighVolumeFixture"));

        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 100);
        long afterFirst = harness.countOf("tiny");
        assertEquals(100, afterFirst);

        harness.config().setSamplingRate(0.0);
        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 100);
        assertEquals(100, harness.countOf("tiny"), "rate 0 stops recording immediately");

        harness.config().setSamplingRate(1.0);
        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 100);
        assertEquals(200, harness.countOf("tiny"), "rate 1 resumes recording");
    }

    @Test
    void mutualRecursionAttributesDepthAcrossMethods() throws Exception {
        Class<?> fixture = load(FIXTURES + "RecursiveFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "RecursiveFixture"));

        invokeStatic(fixture, "mutualA", new Class<?>[]{int.class}, 4);
        // mutualA(4) -> B(3) -> A(2) -> B(1) -> A(0): A at depths 1,3,5; B at 2,4
        MethodStats a = harness.statsFor("mutualA");
        MethodStats b = harness.statsFor("mutualB");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(3, a.count());
        assertEquals(2, b.count());
        assertEquals(5, a.maxDepthObserved());
        assertEquals(4, b.maxDepthObserved());
        assertEquals(1, a.depthCount(1));
        assertEquals(1, a.depthCount(3));
        assertEquals(1, a.depthCount(5));
        assertEquals(1, b.depthCount(2));
        assertEquals(1, b.depthCount(4));
    }

    @Test
    void aggregatorMemoryIsBoundedByConfiguration() throws Exception {
        // Tiny aggregator: 4 methods max, 16 events ring.
        com.github.guoyyit.bytecodetracingjava.tracing.TraceConfig smallConfig =
                new com.github.guoyyit.bytecodetracingjava.tracing.TraceConfig(4, 16);
        com.github.guoyyit.bytecodetracingjava.tracing.MethodRegistry smallRegistry =
                new com.github.guoyyit.bytecodetracingjava.tracing.MethodRegistry();
        TraceAggregator small = new TraceAggregator(smallConfig, smallRegistry);

        for (int method = 0; method < 1000; method++) {
            for (int call = 0; call < 100; call++) {
                small.recordEvent(method, 1L, "t", 1, 10, false, 0L);
            }
        }
        TraceSnapshot snapshot = small.snapshot();
        assertEquals(4, snapshot.methodStats().size(), "method table is bounded");
        assertEquals(100_000 - 400, snapshot.droppedEvents(),
                "events beyond capacity are counted as dropped, not stored");
        assertEquals(16, small.delta(-1).events().size(), "ring buffer is bounded");
        assertTrue(small.delta(-1).gap());
    }

    @Test
    void highVolumeCallsDoNotGrowAggregatorMemory() throws Exception {
        Class<?> fixture = load(FIXTURES + "HighVolumeFixture",
                harness.loaderFor(TraceFilter.all(), FIXTURES + "HighVolumeFixture"));

        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 1_000_000);
        TraceSnapshot first = harness.snapshot();
        invokeStatic(fixture, "loop", new Class<?>[]{int.class}, 1_000_000);
        TraceSnapshot second = harness.snapshot();

        assertEquals(2, second.methodStats().size(), "only loop and tiny are tracked");
        assertEquals(2_000_000, harness.countOf("tiny"),
                "counts keep accumulating without new memory");
        assertEquals(first.methodStats().size(), second.methodStats().size());
    }

    @Test
    void concurrentRecordersNeverLoseConfirmedEvents() throws Exception {
        int threads = 16;
        int eventsPerThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long threadKey = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < eventsPerThread; i++) {
                        harness.aggregator().recordEvent(0, threadKey, "t" + threadKey, 1, 5, false, 0L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        TraceSnapshot snapshot = harness.snapshot();
        assertEquals((long) threads * eventsPerThread, snapshot.totalCount());
        // sequences are dense: no confirmed event was lost
        Set<Long> sequences = new HashSet<>();
        TraceAggregator.Delta delta = harness.aggregator().delta(-1);
        for (TraceEvent event : delta.events()) {
            sequences.add(event.sequence());
        }
        assertEquals(harness.config().getEventBufferCapacity(), sequences.size());
    }
}
