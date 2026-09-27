package com.github.guoyyit.bytecodetracingjava.instrument;

import com.github.guoyyit.bytecodetracingjava.tracing.MethodRegistry;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Set;

/**
 * Second pass: wraps every selected method body with
 * {@code enter} / {@code exit} / {@code error} callbacks into
 * {@link com.github.guoyyit.bytecodetracingjava.tracing.TraceHooks}.
 * The whole body (after the super-constructor call for constructors) is
 * enclosed in a catch-all handler that records the exceptional exit and
 * rethrows, so every frame is counted exactly once on each completion path.
 */
final class InstrumentingClassVisitor extends ClassVisitor {

    private final MethodRegistry registry;
    private final Set<String> selected;
    private String className;

    InstrumentingClassVisitor(ClassVisitor delegate, MethodRegistry registry, Set<String> selected) {
        super(Opcodes.ASM9, delegate);
        this.registry = registry;
        this.selected = selected;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name.replace('/', '.');
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (mv == null || !selected.contains(name + descriptor)) {
            return mv;
        }
        int methodId = registry.register(className, name, descriptor);
        return new TracingMethodAdapter(mv, access, name, descriptor, methodId);
    }

    private static final class TracingMethodAdapter extends AdviceAdapter {

        private static final Type HOOKS = Type.getObjectType(
                "com/github/guoyyit/bytecodetracingjava/tracing/TraceHooks");
        private static final Method ENTER = Method.getMethod("int enter(int)");
        private static final Method EXIT = Method.getMethod("void exit(int)");
        private static final Method ERROR = Method.getMethod("void error(int)");

        private final int methodId;
        private final Label tryStart = new Label();
        private int tokenLocal = -1;

        TracingMethodAdapter(MethodVisitor mv, int access, String name, String descriptor, int methodId) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.methodId = methodId;
        }

        @Override
        protected void onMethodEnter() {
            // For constructors AdviceAdapter calls this only after the super
            // constructor call, so the try region never covers it.
            push(methodId);
            invokeStatic(HOOKS, ENTER);
            tokenLocal = newLocal(Type.INT_TYPE);
            storeLocal(tokenLocal);
            mark(tryStart);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                // One exit hook per return opcode; exactly one runs per call.
                loadLocal(tokenLocal);
                invokeStatic(HOOKS, EXIT);
            }
            // ATHROW inside the body is handled by the catch-all below so an
            // exceptional completion is recorded exactly once.
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            Label tryEnd = new Label();
            mark(tryEnd);
            Label handler = new Label();
            visitTryCatchBlock(tryStart, tryEnd, handler, null);
            mark(handler);
            // Stack: [Throwable]. Record the error, then rethrow unchanged.
            loadLocal(tokenLocal);
            invokeStatic(HOOKS, ERROR);
            visitInsn(ATHROW);
            super.visitMaxs(maxStack, maxLocals);
        }
    }
}
