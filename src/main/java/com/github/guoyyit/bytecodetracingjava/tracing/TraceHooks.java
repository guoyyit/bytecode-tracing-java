package com.github.guoyyit.bytecodetracingjava.tracing;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static callbacks invoked directly from instrumented bytecode.
 * <p>
 * Contract (enforced by the instrumentor): {@link #enter(int)} returns an
 * absolute depth token which the method stores in a local variable; exactly
 * one of {@link #exit(int)} / {@link #error(int)} is later invoked with that
 * token on the same thread. Exits reset the per-thread stack to an
 * <em>absolute</em> size (token&nbsp;-&nbsp;1) instead of a relative pop, so
 * even if a callback cannot run at all — e.g. while unwinding a
 * {@link StackOverflowError} with a completely exhausted stack — the next
 * shallower exit self-heals the depth accounting.
 * <p>
 * These methods never throw and never change business results; when tracing
 * is disabled or the sampling rate is zero they only maintain the (cheap)
 * frame stack. Delivery to the aggregator runs outside the critical path and
 * is retried on later callbacks when it cannot run immediately.
 */
public final class TraceHooks {

    /** Hard cap on tracked frames per thread; deeper frames collapse into an overflow counter. */
    static final int MAX_TRACKED_DEPTH = 16_384;

    private static volatile TraceAggregator aggregator;
    private static volatile TraceConfig config;

    private static final ThreadLocal<FrameStack> STACKS = ThreadLocal.withInitial(FrameStack::new);

    /** Events that could not be delivered nor buffered; diagnostic only. */
    private static final AtomicLong deliveryFailures = new AtomicLong();

    private TraceHooks() {
    }

    public static void install(TraceAggregator aggregator, MethodRegistry registry, TraceConfig config) {
        TraceHooks.aggregator = aggregator;
        TraceHooks.config = config; // volatile write publishes both
    }

    public static void uninstall() {
        TraceHooks.config = null;
        TraceHooks.aggregator = null;
    }

    public static boolean isInstalled() {
        return config != null;
    }

    public static long deliveryFailures() {
        return deliveryFailures.get();
    }

    /**
     * @return the absolute 1-based depth token of the pushed frame; the
     *         caller must hand it back to {@link #exit(int)} /
     *         {@link #error(int)}
     */
    public static int enter(int methodId) {
        FrameStack stack = STACKS.get();
        TraceConfig cfg = config;
        boolean traced = false;
        if (cfg != null && cfg.isEnabled()) {
            int depth = stack.depth();
            if (depth <= cfg.getMaxDepth()) {
                int permyriad = cfg.samplingPermyriad();
                traced = permyriad >= 10_000
                        || (permyriad > 0 && ThreadLocalRandom.current().nextInt(10_000) < permyriad);
            }
        }
        int token = stack.push(methodId, traced ? System.nanoTime() : 0L, traced);
        drain(stack);
        return token;
    }

    public static void exit(int token) {
        FrameStack stack = STACKS.get();
        stack.complete(token, System.nanoTime(), false);
        drain(stack);
    }

    public static void error(int token) {
        FrameStack stack = STACKS.get();
        stack.complete(token, System.nanoTime(), true);
        drain(stack);
    }

    /** Delivers buffered completions; safe to fail, retried on the next callback. */
    private static void drain(FrameStack stack) {
        if (stack.pendingCount == 0) {
            return;
        }
        TraceAggregator agg = aggregator;
        if (agg == null) {
            stack.pendingCount = 0; // pipeline gone; drop quietly
            return;
        }
        Thread thread = Thread.currentThread();
        long threadId = thread.getId();
        String threadName = thread.getName();
        int delivered = 0;
        try {
            for (; delivered < stack.pendingCount; delivered++) {
                agg.recordEvent(stack.pendingMethodIds[delivered], threadId, threadName,
                        stack.pendingDepths[delivered], stack.pendingDurations[delivered],
                        stack.pendingErrors[delivered] != 0, stack.pendingTimestamps[delivered]);
            }
        } catch (Throwable t) {
            // Tracing must never break business code; retry remaining events later.
            deliveryFailures.incrementAndGet();
        }
        if (delivered > 0) {
            stack.compactPending(delivered);
        }
    }

    /** Test/diagnostic aid: current call depth of the calling thread. */
    public static int currentDepth() {
        return STACKS.get().depth() - 1;
    }

    /**
     * Per-thread stack of in-flight instrumented frames plus a small buffer
     * of completed-but-undelivered events. Frames are always pushed (even
     * when not traced) so depth attribution stays correct across sampling
     * decisions. Exits address frames by absolute token, which makes the
     * structure self-healing when a callback cannot run. Grows up to
     * {@link #MAX_TRACKED_DEPTH}; deeper frames are tracked only by count.
     */
    private static final class FrameStack {
        private int[] methodIds = new int[64];
        private long[] startNanos = new long[64];
        private byte[] traced = new byte[64];
        private int size;
        private int overflow;

        private int[] pendingMethodIds = new int[16];
        private long[] pendingDurations = new long[16];
        private long[] pendingTimestamps = new long[16];
        private int[] pendingDepths = new int[16];
        private byte[] pendingErrors = new byte[16];
        private int pendingCount;

        /** @return 1-based depth the next pushed frame will run at. */
        int depth() {
            return size + overflow + 1;
        }

        /** @return the absolute depth token of the new frame. */
        int push(int methodId, long start, boolean isTraced) {
            int token = size + overflow + 1;
            if (token > MAX_TRACKED_DEPTH) {
                overflow++;
                return token;
            }
            if (size == methodIds.length) {
                int newCapacity = Math.min(methodIds.length * 2, MAX_TRACKED_DEPTH);
                methodIds = copyOf(methodIds, newCapacity);
                startNanos = copyOf(startNanos, newCapacity);
                traced = copyOf(traced, newCapacity);
            }
            methodIds[size] = methodId;
            startNanos[size] = start;
            traced[size] = (byte) (isTraced ? 1 : 0);
            size++;
            return token;
        }

        /**
         * Completes the frame identified by {@code token} and resets the
         * stack to the absolute size {@code token - 1}. Must stay shallow
         * and must never throw: it runs while unwinding arbitrary
         * exceptions.
         */
        void complete(int token, long nowNanos, boolean error) {
            int index = token - 1;
            if (index < 0) {
                return; // defensive: corrupt token, ignore
            }
            if (index < size && traced[index] != 0) {
                enqueue(methodIds[index], nowNanos - startNanos[index], token, error);
            }
            int remaining = token - 1;
            if (remaining > size + overflow) {
                remaining = size + overflow; // defensive: never grow the stack on exit
            }
            size = Math.min(remaining, MAX_TRACKED_DEPTH);
            overflow = remaining - size;
        }

        private void enqueue(int methodId, long durationNanos, int depth, boolean error) {
            if (pendingCount == pendingMethodIds.length) {
                int newCapacity = Math.min(pendingMethodIds.length * 2, MAX_TRACKED_DEPTH);
                if (newCapacity <= pendingMethodIds.length) {
                    deliveryFailures.incrementAndGet(); // buffer exhausted; drop this event
                    return;
                }
                try {
                    pendingMethodIds = copyOf(pendingMethodIds, newCapacity);
                    pendingDurations = copyOf(pendingDurations, newCapacity);
                    pendingTimestamps = copyOf(pendingTimestamps, newCapacity);
                    pendingDepths = copyOf(pendingDepths, newCapacity);
                    pendingErrors = copyOf(pendingErrors, newCapacity);
                } catch (Throwable t) {
                    // e.g. StackOverflowError while unwinding: drop the event,
                    // the frame itself is already accounted for.
                    deliveryFailures.incrementAndGet();
                    return;
                }
            }
            pendingMethodIds[pendingCount] = methodId;
            pendingDurations[pendingCount] = durationNanos;
            pendingTimestamps[pendingCount] = System.currentTimeMillis();
            pendingDepths[pendingCount] = depth;
            pendingErrors[pendingCount] = (byte) (error ? 1 : 0);
            pendingCount++;
        }

        void compactPending(int delivered) {
            int remaining = pendingCount - delivered;
            try {
                System.arraycopy(pendingMethodIds, delivered, pendingMethodIds, 0, remaining);
                System.arraycopy(pendingDurations, delivered, pendingDurations, 0, remaining);
                System.arraycopy(pendingTimestamps, delivered, pendingTimestamps, 0, remaining);
                System.arraycopy(pendingDepths, delivered, pendingDepths, 0, remaining);
                System.arraycopy(pendingErrors, delivered, pendingErrors, 0, remaining);
            } catch (Throwable t) {
                deliveryFailures.incrementAndGet();
            }
            pendingCount = remaining;
        }

        private static int[] copyOf(int[] source, int length) {
            int[] copy = new int[length];
            System.arraycopy(source, 0, copy, 0, Math.min(source.length, length));
            return copy;
        }

        private static long[] copyOf(long[] source, int length) {
            long[] copy = new long[length];
            System.arraycopy(source, 0, copy, 0, Math.min(source.length, length));
            return copy;
        }

        private static byte[] copyOf(byte[] source, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(source, 0, copy, 0, Math.min(source.length, length));
            return copy;
        }
    }
}
