package com.github.guoyyit.bytecodetracingjava.support;

import com.github.guoyyit.bytecodetracingjava.instrument.MethodInstrumentor;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodDescriptor;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodRegistry;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodStats;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceAggregator;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceConfig;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceHooks;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceSnapshot;

import java.util.Map;

/**
 * Wires registry/config/aggregator/hooks together for tests and offers
 * convenience accessors. Always call {@link #close()} (or use
 * try-with-resources) so the static hooks do not leak between tests.
 */
public final class TracingTestHarness implements AutoCloseable {

    private final MethodRegistry registry = new MethodRegistry();
    private final TraceConfig config = new TraceConfig();
    private final TraceAggregator aggregator = new TraceAggregator(config, registry);
    private final MethodInstrumentor instrumentor = new MethodInstrumentor(registry);

    public TracingTestHarness() {
        TraceHooks.install(aggregator, registry, config);
    }

    public TraceConfig config() {
        return config;
    }

    public TraceAggregator aggregator() {
        return aggregator;
    }

    public MethodRegistry registry() {
        return registry;
    }

    public MethodInstrumentor instrumentor() {
        return instrumentor;
    }

    public InstrumentingClassLoader loaderFor(TraceFilter filter, String... classNames) {
        return new InstrumentingClassLoader(instrumentor, filter, classNames);
    }

    public TraceSnapshot snapshot() {
        return aggregator.snapshot();
    }

    /** @return stats for the single registered method with this simple name, or null. */
    public MethodStats statsFor(String methodName) {
        TraceSnapshot snapshot = snapshot();
        MethodStats found = null;
        for (Map.Entry<MethodDescriptor, MethodStats> entry : snapshot.methodStats().entrySet()) {
            if (entry.getKey().methodName().equals(methodName)) {
                if (found != null) {
                    throw new IllegalStateException("ambiguous method name: " + methodName);
                }
                found = entry.getValue();
            }
        }
        return found;
    }

    public long countOf(String methodName) {
        MethodStats stats = statsFor(methodName);
        return stats == null ? 0 : stats.count();
    }

    public long errorCountOf(String methodName) {
        MethodStats stats = statsFor(methodName);
        return stats == null ? 0 : stats.errorCount();
    }

    @Override
    public void close() {
        TraceHooks.uninstall();
    }
}
