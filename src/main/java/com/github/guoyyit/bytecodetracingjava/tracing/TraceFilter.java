package com.github.guoyyit.bytecodetracingjava.tracing;

import java.util.List;

/**
 * Decides, at instrumentation time, whether a method is a trace target.
 * Methods that do not match are left completely untouched (zero overhead).
 */
public interface TraceFilter {

    /**
     * @param className        internal (dot-separated) class name
     * @param classAnnotations annotation type descriptors visible on the class
     * @param access           JVM access flags of the method
     * @param methodName       method name (never a constructor/&lt;clinit&gt;)
     * @param descriptor       JVM method descriptor
     * @param annotations      annotation type descriptors visible on the method
     * @return true if the method should be instrumented
     */
    boolean shouldTrace(String className,
                        List<String> classAnnotations,
                        int access,
                        String methodName,
                        String descriptor,
                        List<String> annotations);

    /** A filter that matches every non-synthetic, non-bridge method. */
    static TraceFilter all() {
        return (className, classAnnotations, access, methodName, descriptor, annotations) ->
                (access & 0x1000) == 0 && (access & 0x0040) == 0; // !ACC_SYNTHETIC && !ACC_BRIDGE
    }
}
