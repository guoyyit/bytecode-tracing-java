package com.github.guoyyit.bytecodetracingjava.tests;

import com.github.guoyyit.bytecodetracingjava.support.TracingTestHarness;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilterSpec;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Overhead model (no callbacks in non-target methods) and robustness. */
class OverheadAndRobustnessTest {

    private static final String FIXTURES = "com.github.guoyyit.bytecodetracingjava.fixtures.";
    private static final String HOOKS_OWNER =
            "com/github/guoyyit/bytecodetracingjava/tracing/TraceHooks";

    private TracingTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TracingTestHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void nonTargetMethodsContainZeroCallbackInstructions() throws Exception {
        TraceFilter publicOnly = TraceFilterSpec.builder()
                .visibilities(TraceFilterSpec.Visibility.PUBLIC)
                .build();
        byte[] original;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "com/github/guoyyit/bytecodetracingjava/fixtures/VisibilityFixture.class")) {
            assertNotNull(in);
            original = in.readAllBytes();
        }
        byte[] instrumented = harness.instrumentor().instrument(original, publicOnly,
                getClass().getClassLoader());

        Map<String, Integer> hookCallsPerMethod = countHookCalls(instrumented);
        assertTrue(hookCallsPerMethod.getOrDefault("publicMethod", 0) > 0,
                "target method must contain callbacks");
        assertEquals(0, hookCallsPerMethod.getOrDefault("privateMethod", 0),
                "non-target method must not reference the hooks at all");
        assertEquals(0, hookCallsPerMethod.getOrDefault("protectedMethod", 0));
        assertEquals(0, hookCallsPerMethod.getOrDefault("packageMethod", 0));
    }

    @Test
    void unfilteredClassIsReturnedByteIdentical() throws Exception {
        byte[] original;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "com/github/guoyyit/bytecodetracingjava/fixtures/VisibilityFixture.class")) {
            assertNotNull(in);
            original = in.readAllBytes();
        }
        TraceFilter nothing = TraceFilterSpec.builder()
                .methodNamePattern("matchesNothing").build();
        byte[] result = harness.instrumentor().instrument(original, nothing,
                getClass().getClassLoader());
        assertEquals(java.util.Arrays.toString(sha(original)), java.util.Arrays.toString(sha(result)),
                "no match -> original bytes returned unchanged");
    }

    @Test
    void stackOverflowUnwindsEveryFrameAndDepthResets() throws Exception {
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "RecursiveFixture").loadInstrumented(FIXTURES + "RecursiveFixture");
        Method infinite = fixture.getDeclaredMethod("infinite");

        java.lang.reflect.InvocationTargetException wrapped = assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> infinite.invoke(null));
        assertTrue(wrapped.getCause() instanceof StackOverflowError);

        assertEquals(0, TraceHooks.currentDepth(),
                "per-thread depth must be balanced after the whole stack unwound");

        long errors = harness.errorCountOf("infinite");
        assertEquals(harness.config().getMaxDepth(), errors,
                "every traced frame (up to maxDepth) recorded exactly one error");
        assertEquals(errors, harness.countOf("infinite"));

        // The runtime is still fully functional afterwards.
        Method factorial = fixture.getDeclaredMethod("factorial", int.class);
        assertEquals(6L, factorial.invoke(null, 3));
        assertEquals(3, harness.countOf("factorial"));
    }

    private static byte[] sha(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Integer> countHookCalls(byte[] classBytes) {
        Map<String, Integer> counts = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name2,
                                                String descriptor2, boolean isInterface) {
                        if (HOOKS_OWNER.equals(owner)) {
                            counts.merge(name, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }
}
