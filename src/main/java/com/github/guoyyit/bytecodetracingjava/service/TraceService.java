package com.github.guoyyit.bytecodetracingjava.service;

import com.github.guoyyit.bytecodetracingjava.instrument.MethodInstrumentor;
import com.github.guoyyit.bytecodetracingjava.instrument.RuntimeInstrumentationService;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodRegistry;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceAggregator;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceConfig;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceHooks;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceSnapshot;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * Facade wiring the tracing pipeline together and exposing it to the API
 * layer. Installs the static hooks on startup and removes them on shutdown.
 */
@Service
public class TraceService {

    private final MethodRegistry registry;
    private final TraceConfig config;
    private final TraceAggregator aggregator;
    private final MethodInstrumentor instrumentor;
    private final RuntimeInstrumentationService runtimeInstrumentation;

    public TraceService() {
        this.registry = new MethodRegistry();
        this.config = new TraceConfig();
        this.aggregator = new TraceAggregator(config, registry);
        this.instrumentor = new MethodInstrumentor(registry);
        this.runtimeInstrumentation = new RuntimeInstrumentationService(instrumentor);
        TraceHooks.install(aggregator, registry, config);
    }

    @PreDestroy
    void shutdown() {
        TraceHooks.uninstall();
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

    public RuntimeInstrumentationService runtimeInstrumentation() {
        return runtimeInstrumentation;
    }

    public TraceSnapshot snapshot() {
        return aggregator.snapshot();
    }

    /** Instruments an already-loaded class by name. */
    public void instrumentClass(String className, TraceFilter filter) {
        runtimeInstrumentation.instrumentClass(loadClass(className), filter);
    }

    /** Removes instrumentation from a previously instrumented class. */
    public void detachClass(String className) {
        runtimeInstrumentation.detachClass(loadClass(className));
    }

    private static Class<?> loadClass(String className) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(className, true,
                    contextLoader != null ? contextLoader : TraceService.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("class not found: " + className, e);
        }
    }
}
