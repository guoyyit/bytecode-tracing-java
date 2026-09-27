package com.github.guoyyit.bytecodetracingjava.tracing;

/**
 * Runtime-tunable tracing configuration. All fields are safe to read/write
 * concurrently; readers (instrumented call sites) always observe one
 * consistent value per read.
 */
public class TraceConfig {

    /** Hard cap on the depth histogram buckets, see {@link MethodStats}. */
    public static final int ABSOLUTE_MAX_DEPTH = MethodStats.DEPTH_BUCKETS;

    private volatile boolean enabled = true;
    /** Sampling rate stored as permyriad (0..10_000) to allow int CAS-free reads. */
    private volatile int samplingPermyriad = 10_000;
    private volatile int maxDepth = 64;
    private final int maxMethods;
    private final int eventBufferCapacity;

    public TraceConfig() {
        this(10_000, 1 << 14);
    }

    public TraceConfig(int maxMethods, int eventBufferCapacity) {
        if (maxMethods <= 0) {
            throw new IllegalArgumentException("maxMethods must be positive");
        }
        if (eventBufferCapacity <= 0 || (eventBufferCapacity & (eventBufferCapacity - 1)) != 0) {
            throw new IllegalArgumentException("eventBufferCapacity must be a positive power of two");
        }
        this.maxMethods = maxMethods;
        this.eventBufferCapacity = eventBufferCapacity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return sampling rate in [0.0, 1.0]; 0 disables event recording. */
    public double getSamplingRate() {
        return samplingPermyriad / 10_000.0;
    }

    public void setSamplingRate(double rate) {
        if (rate < 0.0 || rate > 1.0 || Double.isNaN(rate)) {
            throw new IllegalArgumentException("sampling rate must be within [0.0, 1.0]: " + rate);
        }
        this.samplingPermyriad = (int) Math.round(rate * 10_000);
    }

    /** Package-private fast path used by the hot callback. */
    int samplingPermyriad() {
        return samplingPermyriad;
    }

    /** @return maximum call depth (1-based) that is traced; deeper frames are skipped. */
    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        if (maxDepth <= 0 || maxDepth > ABSOLUTE_MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be within [1, " + ABSOLUTE_MAX_DEPTH + "]");
        }
        this.maxDepth = maxDepth;
    }

    /** @return maximum number of distinct methods kept in the aggregator. */
    public int getMaxMethods() {
        return maxMethods;
    }

    /** @return capacity of the bounded completed-event ring buffer. */
    public int getEventBufferCapacity() {
        return eventBufferCapacity;
    }
}
