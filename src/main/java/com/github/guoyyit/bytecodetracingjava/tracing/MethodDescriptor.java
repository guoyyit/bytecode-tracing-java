package com.github.guoyyit.bytecodetracingjava.tracing;

/** Immutable identity of an instrumented method. */
public record MethodDescriptor(int id, String className, String methodName, String descriptor) {

    public String displayName() {
        return className + "#" + methodName + descriptor;
    }
}
