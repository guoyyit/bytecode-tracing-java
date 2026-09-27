package com.github.guoyyit.bytecodetracingjava.fixtures;

/** Exception scenarios: throwing, propagation through frames, catching. */
public class ExceptionFixture {

    public static int thrower() {
        throw new IllegalStateException("boom");
    }

    /** Calls the thrower; the exception propagates through this frame. */
    public static int propagator() {
        return thrower() + 1;
    }

    /** Calls the propagator and catches; completes normally. */
    public static int catcher() {
        try {
            return propagator();
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    /** Throws only when asked to. */
    public static int conditional(boolean fail) {
        if (fail) {
            throw new IllegalArgumentException("asked to fail");
        }
        return 42;
    }
}
