package com.github.guoyyit.bytecodetracingjava.tracing;

/**
 * One completed method invocation.
 *
 * @param sequence     global monotonically increasing sequence number
 * @param methodId     id assigned by {@link MethodRegistry}
 * @param threadId     id of the executing thread
 * @param threadName   name of the executing thread
 * @param depth        1-based call depth at which the frame executed
 * @param durationNanos wall-clock duration of the frame
 * @param error        true if the frame completed by throwing
 * @param timestampMillis completion time, wall clock
 */
public record TraceEvent(long sequence,
                         int methodId,
                         long threadId,
                         String threadName,
                         int depth,
                         long durationNanos,
                         boolean error,
                         long timestampMillis) {
}
