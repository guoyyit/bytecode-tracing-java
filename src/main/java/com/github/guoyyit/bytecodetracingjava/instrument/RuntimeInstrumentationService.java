package com.github.guoyyit.bytecodetracingjava.instrument;

import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds or removes instrumentation on already-loaded classes using
 * {@link java.lang.instrument.Instrumentation}. Retransformation is atomic
 * per class: a concurrent business call executes either the old or the new
 * version of a method, never a mix. After detachment the class bytes are
 * restored to the original ones, leaving no callback references behind.
 * <p>
 * Limitation: target classes must be able to see the tracing runtime (the
 * application class loader); bootstrap classes are not supported.
 */
public class RuntimeInstrumentationService {

    private final MethodInstrumentor instrumentor;
    /** Dotted class name -> active instrumentation target. */
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    /** Dotted class name -> pristine bytes, kept until the class is restored. */
    private final Map<String, byte[]> originals = new ConcurrentHashMap<>();

    private volatile Instrumentation instrumentation;

    public RuntimeInstrumentationService(MethodInstrumentor instrumentor) {
        this.instrumentor = instrumentor;
    }

    /** Attaches to the current JVM. Idempotent. */
    public synchronized void attach() {
        if (instrumentation != null) {
            return;
        }
        final Instrumentation installed;
        try {
            installed = ByteBuddyAgent.install();
        } catch (RuntimeException e) {
            throw new InstrumentationException(
                    "cannot attach to the current JVM; run with -Djdk.attach.allowAttachSelf=true "
                            + "or attach an agent on the command line", e);
        }
        installed.addTransformer(new TracingTransformer(), true);
        this.instrumentation = installed;
    }

    public boolean isAttached() {
        return instrumentation != null;
    }

    /** Instruments an already-loaded class; reapplies if already instrumented. */
    public void instrumentClass(Class<?> target, TraceFilter filter) {
        attach();
        String name = target.getName();
        originals.computeIfAbsent(name, n -> readOriginalBytes(target));
        targets.put(name, new Target(filter));
        retransform(target);
    }

    /** Restores the original bytes of a previously instrumented class. */
    public void detachClass(Class<?> target) {
        String name = target.getName();
        if (targets.remove(name) == null) {
            return;
        }
        try {
            retransform(target); // transformer restores the original bytes
        } finally {
            originals.remove(name);
        }
    }

    public boolean isInstrumented(Class<?> target) {
        return targets.containsKey(target.getName());
    }

    private void retransform(Class<?> target) {
        try {
            instrumentation.retransformClasses(target);
        } catch (Exception e) {
            throw new InstrumentationException(
                    "failed to retransform " + target.getName() + ": " + e.getMessage(), e);
        }
    }

    private static byte[] readOriginalBytes(Class<?> target) {
        String resource = target.getName().replace('.', '/') + ".class";
        ClassLoader loader = target.getClassLoader();
        InputStream in = loader != null
                ? loader.getResourceAsStream(resource)
                : ClassLoader.getSystemResourceAsStream(resource);
        if (in == null) {
            throw new InstrumentationException("cannot locate original class bytes for "
                    + target.getName());
        }
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new InstrumentationException("cannot read original class bytes for "
                    + target.getName(), e);
        }
    }

    private static final class Target {
        final TraceFilter filter;

        Target(TraceFilter filter) {
            this.filter = filter;
        }
    }

    private final class TracingTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            if (className == null) {
                return null;
            }
            String dotted = className.replace('/', '.');
            Target target = targets.get(dotted);
            if (target != null) {
                byte[] original = originals.get(dotted);
                byte[] source = original != null ? original : classfileBuffer;
                try {
                    return instrumentor.instrument(source, target.filter,
                            loader != null ? loader : ClassLoader.getSystemClassLoader());
                } catch (InstrumentationException e) {
                    IllegalClassFormatException wrapped =
                            new IllegalClassFormatException(e.getMessage());
                    wrapped.initCause(e);
                    throw wrapped;
                }
            }
            // Not a target: if we hold pristine bytes this is a detach -> restore.
            return originals.get(dotted);
        }
    }
}
