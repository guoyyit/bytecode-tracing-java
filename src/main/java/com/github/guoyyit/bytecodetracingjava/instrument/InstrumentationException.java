package com.github.guoyyit.bytecodetracingjava.instrument;

/** Raised when a class file cannot be instrumented; no partial output is produced. */
public class InstrumentationException extends RuntimeException {

    public InstrumentationException(String message) {
        super(message);
    }

    public InstrumentationException(String message, Throwable cause) {
        super(message, cause);
    }
}
