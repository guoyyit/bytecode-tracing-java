package com.github.guoyyit.bytecodetracingjava.tests;

import com.github.guoyyit.bytecodetracingjava.instrument.InstrumentationException;
import com.github.guoyyit.bytecodetracingjava.support.TracingTestHarness;
import com.github.guoyyit.bytecodetracingjava.tracing.MethodStats;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline (byte[] -> byte[]) instrumentation behaviour via a custom loader. */
class OfflineInstrumentationTest {

    private static final String FIXTURES = "com.github.guoyyit.bytecodetracingjava.fixtures.";

    private TracingTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TracingTestHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private static Object invokeStatic(Class<?> clazz, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = clazz.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void recursionIsCountedPerFrameWithDepthAttribution() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "RecursiveFixture").loadInstrumented(FIXTURES + "RecursiveFixture");

        long result = (long) invokeStatic(fixture, "factorial", new Class<?>[]{int.class}, 5);
        assertEquals(120L, result);

        MethodStats stats = harness.statsFor("factorial");
        assertNotNull(stats);
        assertEquals(5, stats.count(), "factorial(5) recurses 5 frames deep");
        assertEquals(0, stats.errorCount());
        assertEquals(5, stats.maxDepthObserved());
        // depth histogram: one frame at each depth 1..5
        for (int depth = 1; depth <= 5; depth++) {
            assertEquals(1, stats.depthCount(depth), "depth " + depth);
        }
    }

    @Test
    void fibCountsMatchCallTreeSize() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "RecursiveFixture").loadInstrumented(FIXTURES + "RecursiveFixture");

        invokeStatic(fixture, "fib", new Class<?>[]{int.class}, 6);
        // fib call tree size for n=6: 1 + 2*(calls of children) => 25 frames
        assertEquals(25, harness.countOf("fib"));
    }

    @Test
    void exceptionsAreCountedOncePerFrameAsTheyPropagate() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "ExceptionFixture").loadInstrumented(FIXTURES + "ExceptionFixture");

        assertThrows(IllegalStateException.class,
                () -> invokeStatic(fixture, "propagator", new Class<?>[]{}));

        assertEquals(1, harness.countOf("thrower"));
        assertEquals(1, harness.errorCountOf("thrower"));
        assertEquals(1, harness.countOf("propagator"));
        assertEquals(1, harness.errorCountOf("propagator"));
    }

    @Test
    void caughtExceptionLeavesCatcherAsNormalCompletion() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "ExceptionFixture").loadInstrumented(FIXTURES + "ExceptionFixture");

        int result = (int) invokeStatic(fixture, "catcher", new Class<?>[]{});
        assertEquals(-1, result);

        assertEquals(1, harness.countOf("catcher"));
        assertEquals(0, harness.errorCountOf("catcher"));
        assertEquals(1, harness.errorCountOf("propagator"));
        assertEquals(1, harness.errorCountOf("thrower"));
    }

    @Test
    void finallyAndMultipleReturnsAreCountedExactlyOnce() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "FinallyFixture").loadInstrumented(FIXTURES + "FinallyFixture");

        assertEquals(1, (int) invokeStatic(fixture, "withFinally", new Class<?>[]{boolean.class}, false));
        assertThrows(IllegalStateException.class,
                () -> invokeStatic(fixture, "withFinally", new Class<?>[]{boolean.class}, true));
        assertEquals(2, harness.countOf("withFinally"));
        assertEquals(1, harness.errorCountOf("withFinally"));

        for (int x : new int[]{-5, 0, 4, 7}) {
            invokeStatic(fixture, "multiReturn", new Class<?>[]{int.class}, x);
        }
        assertEquals(4, harness.countOf("multiReturn"));
        assertEquals(0, harness.errorCountOf("multiReturn"));

        assertEquals(7, (int) invokeStatic(fixture, "tryWithResources", new Class<?>[]{}));
        assertEquals(1, harness.countOf("tryWithResources"));

        assertEquals(-1, (int) invokeStatic(fixture, "nestedTry", new Class<?>[]{int.class}, -3));
        assertEquals(5, (int) invokeStatic(fixture, "nestedTry", new Class<?>[]{int.class}, 5));
        assertEquals(2, harness.countOf("nestedTry"));
        assertEquals(0, harness.errorCountOf("nestedTry"), "exception was caught inside the method");
    }

    @Test
    void constructorsAndInstanceMethodsWork() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "ConstructorFixture").loadInstrumented(FIXTURES + "ConstructorFixture");

        Constructor<?> ctor = fixture.getDeclaredConstructor(int.class);
        Object instance = ctor.newInstance(21);
        assertEquals(42, fixture.getDeclaredMethod("doubled").invoke(instance));

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> ctor.newInstance(-1));
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);

        MethodStats ctorStats = harness.statsFor("<init>");
        assertNotNull(ctorStats);
        assertEquals(2, ctorStats.count());
        assertEquals(1, ctorStats.errorCount());
        assertEquals(1, harness.countOf("doubled"));
    }

    @Test
    void visibilityFilterLeavesNonMatchingMethodsUninstrumented() throws Exception {
        TraceFilter publicOnly = TraceFilterSpec.builder()
                .visibilities(TraceFilterSpec.Visibility.PUBLIC)
                .build();
        Class<?> fixture = harness.loaderFor(publicOnly,
                FIXTURES + "VisibilityFixture").loadInstrumented(FIXTURES + "VisibilityFixture");

        Object instance = fixture.getDeclaredConstructor().newInstance();
        assertEquals(10, fixture.getDeclaredMethod("callAll").invoke(instance));

        assertEquals(1, harness.countOf("callAll"));
        assertEquals(1, harness.countOf("publicMethod"));
        assertNull(harness.statsFor("privateMethod"));
        assertNull(harness.statsFor("protectedMethod"));
        assertNull(harness.statsFor("packageMethod"));
    }

    @Test
    void methodNameFilterMatchesOnlySelectedNames() throws Exception {
        TraceFilter byName = TraceFilterSpec.builder().methodNamePattern("fib|factorial").build();
        Class<?> fixture = harness.loaderFor(byName,
                FIXTURES + "RecursiveFixture").loadInstrumented(FIXTURES + "RecursiveFixture");

        invokeStatic(fixture, "factorial", new Class<?>[]{int.class}, 3);
        invokeStatic(fixture, "mutualA", new Class<?>[]{int.class}, 4);

        assertEquals(3, harness.countOf("factorial"));
        assertNull(harness.statsFor("mutualA"));
        assertNull(harness.statsFor("mutualB"));
    }

    @Test
    void annotationFilterMatchesMethodAndClassAnnotations() throws Exception {
        TraceFilter marked = TraceFilterSpec.builder()
                .requiredAnnotations("Lcom/github/guoyyit/bytecodetracingjava/fixtures/TracedMarker;")
                .build();
        Class<?> visibility = harness.loaderFor(marked,
                FIXTURES + "VisibilityFixture", FIXTURES + "AnnotatedClassFixture")
                .loadInstrumented(FIXTURES + "VisibilityFixture");
        Class<?> annotated = visibility.getClassLoader()
                .loadClass(FIXTURES + "AnnotatedClassFixture");

        Object v = visibility.getDeclaredConstructor().newInstance();
        visibility.getDeclaredMethod("annotatedMethod").invoke(v);
        visibility.getDeclaredMethod("notAnnotated").invoke(v);
        Object a = annotated.getDeclaredConstructor().newInstance();
        annotated.getDeclaredMethod("one").invoke(a);

        assertEquals(1, harness.countOf("annotatedMethod"));
        assertNull(harness.statsFor("notAnnotated"));
        assertEquals(1, harness.countOf("one"), "class-level annotation selects all methods");
    }

    @Test
    void malformedClassFileYieldsClearErrorAndNoPartialResult() {
        assertThrows(InstrumentationException.class,
                () -> harness.instrumentor().instrument(new byte[]{1, 2, 3}, TraceFilter.all(),
                        getClass().getClassLoader()));
        assertThrows(InstrumentationException.class,
                () -> harness.instrumentor().instrument(new byte[0], TraceFilter.all(),
                        getClass().getClassLoader()));
        assertThrows(InstrumentationException.class,
                () -> harness.instrumentor().instrument(null, TraceFilter.all(),
                        getClass().getClassLoader()));
    }

    @Test
    void durationsArePositiveAndBoundedByWallClock() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "ConcurrencyFixture").loadInstrumented(FIXTURES + "ConcurrencyFixture");

        long start = System.nanoTime();
        invokeStatic(fixture, "work", new Class<?>[]{long.class, int.class}, 7L, 1_000_000);
        long wall = System.nanoTime() - start;

        MethodStats stats = harness.statsFor("work");
        assertEquals(1, stats.count());
        assertTrue(stats.maxNanos() > 0, "duration must be positive");
        assertTrue(stats.maxNanos() <= wall * 2 + 1_000_000,
                "recorded duration must be within wall clock bounds");
        assertEquals(stats.maxNanos(), stats.minNanos());
    }
}
