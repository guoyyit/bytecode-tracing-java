package com.github.guoyyit.bytecodetracingjava.fixtures;

/** Visibility / annotation scenarios for filter tests. */
public class VisibilityFixture {

    public int publicMethod() {
        return 1;
    }

    protected int protectedMethod() {
        return 2;
    }

    int packageMethod() {
        return 3;
    }

    private int privateMethod() {
        return 4;
    }

    @TracedMarker
    public int annotatedMethod() {
        return 5;
    }

    public int notAnnotated() {
        return 6;
    }

    public int callAll() {
        return publicMethod() + protectedMethod() + packageMethod() + privateMethod();
    }
}
