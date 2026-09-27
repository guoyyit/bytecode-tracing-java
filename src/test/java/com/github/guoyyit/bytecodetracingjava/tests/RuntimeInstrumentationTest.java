package com.github.guoyyit.bytecodetracingjava.tests;

import com.github.guoyyit.bytecodetracingjava.fixtures.ConcurrencyFixture;
import com.github.guoyyit.bytecodetracingjava.fixtures.HighVolumeFixture;
import com.github.guoyyit.bytecodetracingjava.instrument.RuntimeInstrumentationService;
import com.github.guoyyit.bytecodetracingjava.support.TracingTestHarness;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime attach/detach of instrumentation on already-loaded classes. */
class RuntimeInstrumentationTest {

    private TracingTestHarness harness;
    private RuntimeInstrumentationService runtime;

    @BeforeEach
    void setUp() {
        harness = new TracingTestHarness();
        runtime = new RuntimeInstrumentationService(harness.instrumentor());
    }

    @AfterEach
    void tearDown() {
        if (runtime.isInstrumented(HighVolumeFixture.class)) {
            runtime.detachClass(HighVolumeFixture.class);
        }
        if (runtime.isInstrumented(ConcurrencyFixture.class)) {
            runtime.detachClass(ConcurrencyFixture.class);
        }
        harness.close();
    }

    @Test
    void attachThenDetachTogglesTracing() {
        // Already loaded by the system loader through this direct reference.
        long before = HighVolumeFixture.loop(10);
        assertEquals(0, harness.snapshot().totalCount(), "not instrumented yet");

        runtime.instrumentClass(HighVolumeFixture.class, TraceFilter.all());
        assertTrue(runtime.isInstrumented(HighVolumeFixture.class));
        long after = HighVolumeFixture.loop(10);
        assertEquals(before, after, "business result unchanged");
        assertEquals(11, harness.snapshot().totalCount(), "loop + 10 tiny calls recorded");

        runtime.detachClass(HighVolumeFixture.class);
        assertFalse(runtime.isInstrumented(HighVolumeFixture.class));
        harness.aggregator().reset();
        long detached = HighVolumeFixture.loop(10);
        assertEquals(before, detached);
        assertEquals(0, harness.snapshot().totalCount(),
                "no residual callbacks after detach");
    }

    @Test
    void reInstrumentingWithAnotherFilterReplacesThePreviousOne() {
        runtime.instrumentClass(HighVolumeFixture.class, TraceFilter.all());
        HighVolumeFixture.loop(5);
        assertEquals(6, harness.snapshot().totalCount());

        TraceFilter loopOnly = TraceFilterSpec.builder().methodNamePattern("loop").build();
        runtime.instrumentClass(HighVolumeFixture.class, loopOnly);
        harness.aggregator().reset();
        HighVolumeFixture.loop(5);
        assertEquals(1, harness.snapshot().totalCount(), "only loop is traced now");
    }

    @Test
    void concurrentToggleKeepsEveryCallInOneDeterminedState() throws Exception {
        int threads = 4;
        AtomicLong expected = new AtomicLong();
        AtomicLong totalCalls = new AtomicLong();
        long[] callsPerThread = new long[threads];
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int index = t;
            long seed = t + 1;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    long calls = 0;
                    while (!stop.get()) {
                        long value = ConcurrencyFixture.work(seed, 100);
                        expected.addAndGet(value);
                        calls++;
                    }
                    callsPerThread[index] = calls;
                    totalCalls.addAndGet(calls);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();

        // Flip instrumentation on/off while calls are in flight.
        for (int i = 0; i < 10; i++) {
            runtime.instrumentClass(ConcurrencyFixture.class, TraceFilter.all());
            Thread.sleep(3);
            runtime.detachClass(ConcurrencyFixture.class);
            Thread.sleep(3);
        }
        stop.set(true);
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Every call returned the correct business result: work() is
        // deterministic, so the expected sum is exactly reproducible.
        long recomputed = 0;
        for (int t = 0; t < threads; t++) {
            recomputed += callsPerThread[t] * ConcurrencyFixture.work(t + 1, 100);
        }
        assertEquals(recomputed, expected.get(),
                "all calls produced correct results regardless of instrumentation state");

        long recorded = harness.snapshot().totalCount();
        assertTrue(recorded > 0, "some calls were recorded while instrumented");
        assertTrue(recorded <= totalCalls.get(),
                "a call is counted at most once: recorded=" + recorded
                        + " calls=" + totalCalls.get());

        // After the final detach, no more events arrive.
        harness.aggregator().reset();
        ConcurrencyFixture.work(1, 10);
        assertEquals(0, harness.snapshot().totalCount());
    }

    @Test
    void detachedClassHasNoResidualCallbackReferences() {
        runtime.instrumentClass(HighVolumeFixture.class, TraceFilter.all());
        HighVolumeFixture.loop(3);
        assertTrue(harness.snapshot().totalCount() > 0);

        runtime.detachClass(HighVolumeFixture.class);
        // Hooks stay installed: any leftover callback would still record.
        harness.aggregator().reset();
        HighVolumeFixture.loop(3);
        HighVolumeFixture.tiny(1);
        assertEquals(0, harness.snapshot().totalCount(),
                "restored class bytes must not reference the trace hooks");
    }
}
