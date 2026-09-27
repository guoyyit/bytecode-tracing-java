package com.github.guoyyit.bytecodetracingjava.tests;

import com.github.guoyyit.bytecodetracingjava.fixtures.ExceptionFixture;
import com.github.guoyyit.bytecodetracingjava.support.TracingTestHarness;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Instrumented classes keep line numbers, locals, signatures and verifiability. */
class BytecodePreservationTest {

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

    private static byte[] readClassBytes(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = BytecodePreservationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource);
            return in.readAllBytes();
        }
    }

    @Test
    void genericSignaturesSurviveInstrumentation() throws Exception {
        byte[] original = readClassBytes(FIXTURES + "GenericFixture");
        byte[] instrumented = harness.instrumentor().instrument(original, TraceFilter.all(),
                getClass().getClassLoader());

        Map<String, String> originalSignatures = signaturesOf(original);
        Map<String, String> instrumentedSignatures = signaturesOf(instrumented);
        assertEquals(originalSignatures, instrumentedSignatures,
                "generic signatures must be preserved");
        assertTrue(originalSignatures.containsKey("identity"),
                "fixture must actually have a generic signature");
    }

    @Test
    void localVariablesAndLineNumbersSurvive() throws Exception {
        byte[] original = readClassBytes(FIXTURES + "GenericFixture");
        byte[] instrumented = harness.instrumentor().instrument(original, TraceFilter.all(),
                getClass().getClassLoader());

        List<String> locals = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        new ClassReader(instrumented).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("copyNames")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLocalVariable(String name, String descriptor, String signature,
                                                   Label start, Label end, int index) {
                        locals.add(name);
                    }

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        lines.add(line);
                    }
                };
            }
        }, 0);

        assertTrue(locals.contains("input") && locals.contains("copy") && locals.contains("size"),
                "local variable table must be preserved, got " + locals);
        assertFalse(lines.isEmpty(), "line numbers must be preserved");
    }

    @Test
    void stackTraceLineNumbersMatchTheOriginalClass() throws Exception {
        // Original (uninstrumented) reference line number.
        int originalLine = -1;
        try {
            ExceptionFixture.thrower();
        } catch (IllegalStateException e) {
            originalLine = e.getStackTrace()[0].getLineNumber();
        }
        assertTrue(originalLine > 0, "fixture must have thrown");

        Class<?> instrumented = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "ExceptionFixture").loadInstrumented(FIXTURES + "ExceptionFixture");
        Method thrower = instrumented.getDeclaredMethod("thrower");
        try {
            thrower.invoke(null);
            fail("must throw");
        } catch (java.lang.reflect.InvocationTargetException e) {
            StackTraceElement top = e.getCause().getStackTrace()[0];
            assertEquals(originalLine, top.getLineNumber(),
                    "throw site line number must be preserved");
            assertEquals("thrower", top.getMethodName());
        }
    }

    @Test
    void instrumentedClassVerifiesAndExecutes() throws Exception {
        // Loading already verifies; additionally exercise a generic method.
        Class<?> fixture = harness.loaderFor(TraceFilter.all(),
                FIXTURES + "GenericFixture").loadInstrumented(FIXTURES + "GenericFixture");
        Method identity = fixture.getDeclaredMethod("identity", Number.class);
        assertEquals(42, identity.invoke(null, 42));
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) fixture
                .getDeclaredMethod("copyNames", List.class)
                .invoke(null, List.of("a", "b"));
        assertEquals(List.of("a", "b"), result);
        assertEquals(1, harness.countOf("identity"));
        assertEquals(1, harness.countOf("copyNames"));
    }

    private static Map<String, String> signaturesOf(byte[] classBytes) {
        Map<String, String> signatures = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (signature != null) {
                    signatures.put(name, signature);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return signatures;
    }
}
