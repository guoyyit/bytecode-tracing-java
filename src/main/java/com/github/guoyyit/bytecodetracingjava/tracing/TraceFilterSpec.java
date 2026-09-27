package com.github.guoyyit.bytecodetracingjava.tracing;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configurable {@link TraceFilter}: matches on method visibility, method-name
 * pattern and annotations (on the method or its declaring class). All set
 * conditions must hold (AND semantics).
 */
public final class TraceFilterSpec implements TraceFilter {

    private final Set<Visibility> visibilities;
    private final Pattern methodNamePattern;
    private final Set<String> requiredAnnotations;

    private TraceFilterSpec(Builder builder) {
        this.visibilities = builder.visibilities;
        this.methodNamePattern = builder.methodNamePattern;
        this.requiredAnnotations = builder.requiredAnnotations;
    }

    public enum Visibility {
        PUBLIC, PROTECTED, PACKAGE, PRIVATE
    }

    @Override
    public boolean shouldTrace(String className,
                               List<String> classAnnotations,
                               int access,
                               String methodName,
                               String descriptor,
                               List<String> annotations) {
        if ((access & 0x1000) != 0 || (access & 0x0040) != 0) {
            return false; // ACC_SYNTHETIC / ACC_BRIDGE
        }
        if (!visibilities.contains(visibilityOf(access))) {
            return false;
        }
        if (methodNamePattern != null && !methodNamePattern.matcher(methodName).matches()) {
            return false;
        }
        if (!requiredAnnotations.isEmpty()) {
            for (String required : requiredAnnotations) {
                if (!annotations.contains(required) && !classAnnotations.contains(required)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Visibility visibilityOf(int access) {
        if (Modifier.isPublic(access)) {
            return Visibility.PUBLIC;
        }
        if (Modifier.isProtected(access)) {
            return Visibility.PROTECTED;
        }
        if (Modifier.isPrivate(access)) {
            return Visibility.PRIVATE;
        }
        return Visibility.PACKAGE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Set<Visibility> visibilities = Set.of(Visibility.PUBLIC, Visibility.PROTECTED,
                Visibility.PACKAGE, Visibility.PRIVATE);
        private Pattern methodNamePattern;
        private Set<String> requiredAnnotations = Set.of();

        public Builder visibilities(Visibility... visibilities) {
            this.visibilities = Set.of(visibilities);
            return this;
        }

        public Builder methodNamePattern(String regex) {
            this.methodNamePattern = regex == null ? null : Pattern.compile(regex);
            return this;
        }

        /** Annotation descriptors, e.g. "Lcom/example/Traced;". All must be present. */
        public Builder requiredAnnotations(String... descriptors) {
            this.requiredAnnotations = Set.of(descriptors);
            return this;
        }

        public TraceFilterSpec build() {
            return new TraceFilterSpec(this);
        }
    }
}
