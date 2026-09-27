package com.github.guoyyit.bytecodetracingjava.tracing;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Mutable, fixed-size aggregate for a single method. Memory usage does not
 * depend on the number of recorded invocations. All operations are
 * thread-safe; {@link #snapshotCopy()} produces a consistent copy when the
 * caller holds the aggregator's write lock.
 */
public final class MethodStats {

    /** Depth histogram is capped; deeper frames fold into the last bucket. */
    public static final int DEPTH_BUCKETS = 65;

    private final AtomicLong count = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();
    /** Stored inverted (Long.MAX_VALUE - nanos) so AtomicLong.getAndUpdate keeps it a pure min. */
    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxDepthObserved = new AtomicLong();
    private final AtomicLongArray depthHistogram = new AtomicLongArray(DEPTH_BUCKETS);

    public MethodStats() {
    }

    private MethodStats(long count, long errorCount, long totalNanos, long maxNanos, long minNanos,
                        long maxDepthObserved, long[] histogram) {
        this.count.set(count);
        this.errorCount.set(errorCount);
        this.totalNanos.set(totalNanos);
        this.maxNanos.set(maxNanos);
        this.minNanos.set(minNanos);
        this.maxDepthObserved.set(maxDepthObserved);
        for (int i = 0; i < DEPTH_BUCKETS; i++) {
            this.depthHistogram.set(i, histogram[i]);
        }
    }

    public void record(long durationNanos, boolean error, int depth) {
        count.incrementAndGet();
        if (error) {
            errorCount.incrementAndGet();
        }
        totalNanos.addAndGet(durationNanos);
        maxNanos.getAndUpdate(current -> Math.max(current, durationNanos));
        minNanos.getAndUpdate(current -> Math.min(current, durationNanos));
        int bucket = Math.min(depth, DEPTH_BUCKETS - 1);
        depthHistogram.incrementAndGet(bucket);
        maxDepthObserved.getAndUpdate(current -> Math.max(current, depth));
    }

    public long count() {
        return count.get();
    }

    public long errorCount() {
        return errorCount.get();
    }

    public long totalNanos() {
        return totalNanos.get();
    }

    public long maxNanos() {
        return maxNanos.get();
    }

    /** @return minimum observed duration, or 0 when no event was recorded. */
    public long minNanos() {
        long min = minNanos.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    public long maxDepthObserved() {
        return maxDepthObserved.get();
    }

    public long depthCount(int bucket) {
        return depthHistogram.get(bucket);
    }

    /** @return an independent, consistent copy of this aggregate. */
    public MethodStats snapshotCopy() {
        long[] histogram = new long[DEPTH_BUCKETS];
        for (int i = 0; i < DEPTH_BUCKETS; i++) {
            histogram[i] = depthHistogram.get(i);
        }
        return new MethodStats(count.get(), errorCount.get(), totalNanos.get(), maxNanos.get(),
                minNanos.get(), maxDepthObserved.get(), histogram);
    }
}
