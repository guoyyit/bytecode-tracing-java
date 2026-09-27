package com.github.guoyyit.bytecodetracingjava.support;

import com.github.guoyyit.bytecodetracingjava.instrument.MethodInstrumentor;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Loads selected classes from their original .class resources, runs them
 * through a {@link MethodInstrumentor} and defines the result. Everything
 * else (crucially the tracing runtime itself) is delegated to the parent so
 * instrumented code and the test share one {@code TraceHooks} state.
 */
public final class InstrumentingClassLoader extends ClassLoader {

    private final MethodInstrumentor instrumentor;
    private final TraceFilter filter;
    private final Set<String> instrumentedNames;

    public InstrumentingClassLoader(MethodInstrumentor instrumentor, TraceFilter filter,
                                    String... classNames) {
        super(InstrumentingClassLoader.class.getClassLoader());
        this.instrumentor = instrumentor;
        this.filter = filter;
        this.instrumentedNames = Set.of(classNames);
    }

    public Class<?> loadInstrumented(String name) {
        try {
            return loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (instrumentedNames.contains(name)) {
                    loaded = defineInstrumented(name);
                } else {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> defineInstrumented(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        byte[] original;
        try (InputStream in = getParent().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ClassNotFoundException("no resource for " + name);
            }
            original = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException("cannot read " + resource, e);
        }
        byte[] transformed = instrumentor.instrument(original, filter, this);
        return defineClass(name, transformed, 0, transformed.length);
    }
}
