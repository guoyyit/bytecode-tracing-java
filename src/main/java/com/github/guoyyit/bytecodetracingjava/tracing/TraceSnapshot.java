package com.github.guoyyit.bytecodetracingjava.tracing;

import java.util.List;
import java.util.Map;

/**
 * Immutable, self-consistent view of the aggregator at one point in time:
 * every aggregate reflects exactly the events with sequence &lt;
 * {@link #sequence()}.
 *
 * @param sequence        sequence number one past the last included event
 * @param timestampMillis wall-clock time the snapshot was taken
 * @param methodStats     per-method aggregates keyed by method descriptor
 * @param threadStats     per-thread aggregates keyed by thread id
 * @param droppedEvents   events never aggregated because capacity was reached
 */
public record TraceSnapshot(long sequence,
                            long timestampMillis,
                            Map<MethodDescriptor, MethodStats> methodStats,
                            Map<Long, ThreadStatsView> threadStats,
                            long droppedEvents) {

    /** Immutable per-thread aggregate. */
    public record ThreadStatsView(String threadName, long count, long errorCount, long totalNanos) {
    }

    /** @return entries whose method name contains the given fragment. */
    public List<Map.Entry<MethodDescriptor, MethodStats>> byMethodName(String nameFragment) {
        return methodStats.entrySet().stream()
                .filter(e -> e.getKey().methodName().contains(nameFragment)
                        || e.getKey().displayName().contains(nameFragment))
                .toList();
    }

    /** @return total number of completed invocations reflected in this snapshot. */
    public long totalCount() {
        return methodStats.values().stream().mapToLong(MethodStats::count).sum();
    }
}
