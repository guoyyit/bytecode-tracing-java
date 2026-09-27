package com.github.guoyyit.bytecodetracingjava.tracing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bounded, concurrent aggregation of completed trace events.
 * <p>
 * Concurrency model: {@link #record(TraceEvent)} runs under a read lock, so
 * any number of recording threads proceed in parallel; {@link #snapshot()}
 * and {@link #reset()} take the write lock and therefore observe exactly the
 * events with sequence &lt; the current sequence — a self-consistent
 * point-in-time view. No lock ordering issues exist because only this one
 * lock is ever taken.
 * <p>
 * Memory is bounded by {@code maxMethods} fixed-size per-method entries, a
 * bounded per-thread table and a fixed-size event ring buffer; usage never
 * grows with the number of recorded calls.
 */
public class TraceAggregator {

    private static final int MAX_THREADS = 4096;

    private final TraceConfig config;
    private final MethodRegistry registry;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<Integer, MethodStats> stats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ThreadStats> threadStats = new ConcurrentHashMap<>();
    private final AtomicLong methodEntries = new AtomicLong();
    private final AtomicLong threadEntries = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReferenceArray<TraceEvent> ring;
    private final int ringMask;

    public TraceAggregator(TraceConfig config, MethodRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.ring = new AtomicReferenceArray<>(config.getEventBufferCapacity());
        this.ringMask = config.getEventBufferCapacity() - 1;
    }

    /**
     * Records one completed invocation. The sequence number is assigned
     * inside the read lock, so a snapshot taken afterwards either includes
     * this event everywhere or nowhere. Never blocks indefinitely and never
     * throws.
     */
    public void recordEvent(int methodId, long threadId, String threadName, int depth,
                            long durationNanos, boolean error, long timestampMillis) {
        lock.readLock().lock();
        try {
            long seq = sequence.getAndIncrement();
            TraceEvent event = new TraceEvent(seq, methodId, threadId, threadName, depth,
                    durationNanos, error, timestampMillis);
            ring.set((int) (seq & ringMask), event);

            MethodStats methodStats = stats.get(methodId);
            if (methodStats == null) {
                methodStats = tryCreateMethodStats(methodId);
            }
            if (methodStats != null) {
                methodStats.record(durationNanos, error, depth);
            } else {
                droppedEvents.incrementAndGet();
            }

            ThreadStats ts = threadStats.get(threadId);
            if (ts == null) {
                ts = tryCreateThreadStats(threadId, threadName);
            }
            if (ts != null) {
                ts.record(durationNanos, error);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    private MethodStats tryCreateMethodStats(int methodId) {
        if (methodEntries.get() >= config.getMaxMethods()) {
            return null;
        }
        MethodStats fresh = new MethodStats();
        long ticket = methodEntries.incrementAndGet();
        if (ticket > config.getMaxMethods()) {
            methodEntries.decrementAndGet();
            return null;
        }
        MethodStats raced = stats.putIfAbsent(methodId, fresh);
        if (raced != null) {
            methodEntries.decrementAndGet();
            return raced;
        }
        return fresh;
    }

    private ThreadStats tryCreateThreadStats(long threadId, String threadName) {
        if (threadEntries.get() >= MAX_THREADS) {
            return null;
        }
        ThreadStats fresh = new ThreadStats(threadName);
        long ticket = threadEntries.incrementAndGet();
        if (ticket > MAX_THREADS) {
            threadEntries.decrementAndGet();
            return null;
        }
        ThreadStats raced = threadStats.putIfAbsent(threadId, fresh);
        if (raced != null) {
            threadEntries.decrementAndGet();
            return raced;
        }
        return fresh;
    }

    /** @return a consistent point-in-time snapshot of all aggregates. */
    public TraceSnapshot snapshot() {
        lock.writeLock().lock();
        try {
            long seq = sequence.get();
            Map<MethodDescriptor, MethodStats> methodView = new LinkedHashMap<>();
            for (Map.Entry<Integer, MethodStats> entry : stats.entrySet()) {
                MethodDescriptor descriptor = registry.lookup(entry.getKey())
                        .orElse(new MethodDescriptor(entry.getKey(), "<unregistered>", "<unknown>", ""));
                methodView.put(descriptor, entry.getValue().snapshotCopy());
            }
            Map<Long, TraceSnapshot.ThreadStatsView> threadView = new LinkedHashMap<>();
            for (Map.Entry<Long, ThreadStats> entry : threadStats.entrySet()) {
                threadView.put(entry.getKey(), entry.getValue().view());
            }
            return new TraceSnapshot(seq, System.currentTimeMillis(), methodView, threadView,
                    droppedEvents.get());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns events with sequence &gt;= {@code sinceSequence} (exclusive).
     * The result flags a gap when events in the requested range were already
     * evicted from the bounded ring buffer.
     */
    public Delta delta(long sinceSequence) {
        lock.readLock().lock();
        try {
            long current = sequence.get();
            if (sinceSequence >= current - 1 && sinceSequence >= 0) {
                return new Delta(sinceSequence, current, false, List.of());
            }
            long oldestAvailable = Math.max(0, current - ring.length());
            long from = Math.max(sinceSequence + 1, oldestAvailable);
            boolean gap = sinceSequence + 1 < oldestAvailable;
            List<TraceEvent> events = new ArrayList<>((int) (current - from));
            for (long s = from; s < current; s++) {
                TraceEvent e = ring.get((int) (s & ringMask));
                if (e != null && e.sequence() == s) {
                    events.add(e);
                }
            }
            return new Delta(from, current, gap, events);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Best-effort scan of the recent-event ring buffer by method, thread and
     * time window. Individual events are immutable so every returned event
     * satisfies the filter; boundary events may race with concurrent writers.
     */
    public List<TraceEvent> query(Integer methodId, Long threadId, long fromMillis, long toMillis, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            long current = sequence.get();
            long oldestAvailable = Math.max(0, current - ring.length());
            List<TraceEvent> result = new ArrayList<>();
            for (long s = current - 1; s >= oldestAvailable && result.size() < limit; s--) {
                TraceEvent e = ring.get((int) (s & ringMask));
                if (e == null || e.sequence() != s) {
                    continue;
                }
                if (methodId != null && e.methodId() != methodId) {
                    continue;
                }
                if (threadId != null && e.threadId() != threadId) {
                    continue;
                }
                if (e.timestampMillis() < fromMillis || e.timestampMillis() > toMillis) {
                    continue;
                }
                result.add(e);
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** @return sequence number one past the most recently recorded event. */
    public long currentSequence() {
        return sequence.get();
    }

    public void reset() {
        lock.writeLock().lock();
        try {
            stats.clear();
            threadStats.clear();
            methodEntries.set(0);
            threadEntries.set(0);
            droppedEvents.set(0);
            sequence.set(0);
            for (int i = 0; i < ring.length(); i++) {
                ring.set(i, null);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Mutable per-thread aggregate. */
    private static final class ThreadStats {
        private final String name;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong errorCount = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();

        ThreadStats(String name) {
            this.name = name;
        }

        void record(long durationNanos, boolean error) {
            count.incrementAndGet();
            if (error) {
                errorCount.incrementAndGet();
            }
            totalNanos.addAndGet(durationNanos);
        }

        TraceSnapshot.ThreadStatsView view() {
            return new TraceSnapshot.ThreadStatsView(name, count.get(), errorCount.get(), totalNanos.get());
        }
    }

    public record Delta(long fromSequence, long toSequence, boolean gap, List<TraceEvent> events) {
    }
}
