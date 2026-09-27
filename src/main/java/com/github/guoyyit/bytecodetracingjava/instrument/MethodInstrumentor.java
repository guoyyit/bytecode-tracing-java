package com.github.guoyyit.bytecodetracingjava.instrument;

import com.github.guoyyit.bytecodetracingjava.tracing.MethodRegistry;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceFilter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Transforms class-file bytes, injecting method-entry / normal-exit /
 * exceptional-exit callbacks into every method selected by a
 * {@link TraceFilter}. Original behaviour, line numbers, local variables and
 * generic signatures are preserved; stack-map frames are recomputed so the
 * result always verifies.
 * <p>
 * The transformation is all-or-nothing: malformed or unsupported input raises
 * {@link InstrumentationException} and no partial result is returned. When no
 * method matches the filter the original bytes are returned unchanged.
 */
public class MethodInstrumentor {

    private final MethodRegistry registry;

    public MethodInstrumentor(MethodRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param classBytes  original class-file bytes
     * @param filter      selects which methods get instrumented
     * @param classLoader loader used to resolve types while recomputing frames
     * @return instrumented class-file bytes, or the input bytes when nothing matched
     * @throws InstrumentationException if the input is malformed or unsupported
     */
    public byte[] instrument(byte[] classBytes, TraceFilter filter, ClassLoader classLoader) {
        if (classBytes == null || classBytes.length == 0) {
            throw new InstrumentationException("empty class file");
        }
        final ClassReader reader;
        try {
            reader = new ClassReader(classBytes);
        } catch (RuntimeException e) {
            throw new InstrumentationException("malformed class file: " + e.getMessage(), e);
        }

        // Pass 1: select target methods.
        final MethodSelector selector = new MethodSelector(filter);
        try {
            reader.accept(selector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            throw new InstrumentationException(
                    "failed to scan class " + safeClassName(reader) + ": " + e.getMessage(), e);
        }
        Set<String> selected = selector.selectedMethods();
        if (selected.isEmpty()) {
            return classBytes;
        }

        // Pass 2: rewrite selected methods. Frames are recomputed; the
        // hierarchy resolver knows the instrumented class itself plus
        // everything loadable through the supplied class loader.
        HierarchyResolver hierarchy = new HierarchyResolver(classLoader,
                reader.getClassName(), reader.getSuperName(), reader.getInterfaces(),
                (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return hierarchy.commonSuperClass(type1, type2);
            }
        };
        try {
            InstrumentingClassVisitor visitor = new InstrumentingClassVisitor(writer, registry, selected);
            reader.accept(visitor, ClassReader.SKIP_FRAMES);
            return writer.toByteArray();
        } catch (InstrumentationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InstrumentationException(
                    "failed to instrument class " + reader.getClassName().replace('/', '.')
                            + ": " + e.getMessage(), e);
        }
    }

    private static String safeClassName(ClassReader reader) {
        try {
            return reader.getClassName().replace('/', '.');
        } catch (RuntimeException e) {
            return "<unreadable>";
        }
    }

    /**
     * Resolves common superclasses on internal names without defining new
     * classes: the instrumented class contributes its own hierarchy,
     * everything else is resolved through reflection against the supplied
     * loader. Fails loudly — a wrong answer here would produce unverifiable
     * bytecode.
     */
    private static final class HierarchyResolver {
        private final ClassLoader loader;
        private final String ownName;
        private final String ownSuper;
        private final String[] ownInterfaces;
        private final boolean ownIsInterface;

        HierarchyResolver(ClassLoader loader, String ownName, String ownSuper,
                          String[] ownInterfaces, boolean ownIsInterface) {
            this.loader = loader == null ? ClassLoader.getSystemClassLoader() : loader;
            this.ownName = ownName;
            this.ownSuper = ownSuper;
            this.ownInterfaces = ownInterfaces == null ? new String[0] : ownInterfaces;
            this.ownIsInterface = ownIsInterface;
        }

        String commonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            if (isAssignable(type1, type2)) {
                return type1;
            }
            if (isAssignable(type2, type1)) {
                return type2;
            }
            if (isInterface(type1) || isInterface(type2)) {
                return "java/lang/Object";
            }
            String current = superOf(type1);
            while (current != null && !isAssignable(current, type2)) {
                current = superOf(current);
            }
            return current == null ? "java/lang/Object" : current;
        }

        /** @return true if a value of type {@code sub} can be assigned to {@code sup}. */
        private boolean isAssignable(String sup, String sub) {
            Deque<String> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(sub);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!seen.add(current)) {
                    continue;
                }
                if (current.equals(sup)) {
                    return true;
                }
                String superName = superOf(current);
                if (superName != null) {
                    queue.add(superName);
                }
                for (String iface : interfacesOf(current)) {
                    queue.add(iface);
                }
            }
            return false;
        }

        private boolean isInterface(String internalName) {
            if (internalName.equals(ownName)) {
                return ownIsInterface;
            }
            return load(internalName).isInterface();
        }

        private String superOf(String internalName) {
            if (internalName.equals(ownName)) {
                return ownSuper;
            }
            Class<?> superclass = load(internalName).getSuperclass();
            return superclass == null ? null : superclass.getName().replace('.', '/');
        }

        private String[] interfacesOf(String internalName) {
            if (internalName.equals(ownName)) {
                return ownInterfaces;
            }
            Class<?>[] interfaces = load(internalName).getInterfaces();
            String[] result = new String[interfaces.length];
            for (int i = 0; i < interfaces.length; i++) {
                result[i] = interfaces[i].getName().replace('.', '/');
            }
            return result;
        }

        private Class<?> load(String internalName) {
            try {
                return Class.forName(internalName.replace('/', '.'), false, loader);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                throw new InstrumentationException(
                        "cannot resolve type " + internalName.replace('/', '.')
                                + " while recomputing stack frames; supply a class loader that can see it",
                        e);
            }
        }
    }
}
