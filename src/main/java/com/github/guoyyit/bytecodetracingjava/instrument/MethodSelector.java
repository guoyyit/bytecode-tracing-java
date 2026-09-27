package com.github.guoyyit.bytecodetracingjava.instrument;

import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * First pass over a class file: collects class/method annotations and decides
 * via a {@link TraceFilter} which methods become trace targets. Runs with
 * {@code SKIP_CODE} so it is cheap.
 */
final class MethodSelector extends ClassVisitor {

    private final TraceFilter filter;
    private final Set<String> selected = new HashSet<>();
    private final List<String> classAnnotations = new ArrayList<>();
    private String className;

    MethodSelector(TraceFilter filter) {
        super(Opcodes.ASM9);
        this.filter = filter;
    }

    /** @return selected method keys (name+descriptor). */
    Set<String> selectedMethods() {
        return selected;
    }

    String className() {
        return className;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name.replace('/', '.');
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        classAnnotations.add(descriptor);
        return null;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        if ("<clinit>".equals(name)) {
            return null; // never instrument class initializers
        }
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return null; // no code to instrument
        }
        return new MethodVisitor(Opcodes.ASM9) {
            private final List<String> annotations = new ArrayList<>();

            @Override
            public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                annotations.add(annotationDescriptor);
                return null;
            }

            @Override
            public void visitEnd() {
                if (filter.shouldTrace(className, classAnnotations, access, name, descriptor, annotations)) {
                    selected.add(name + descriptor);
                }
            }
        };
    }
}
