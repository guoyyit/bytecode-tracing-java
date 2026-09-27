package com.github.guoyyit.bytecodetracingjava.fixtures;

/** Constructor + instance method scenario, including a throwing constructor. */
public class ConstructorFixture {

    private final int value;

    public ConstructorFixture(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative");
        }
        this.value = value;
    }

    public ConstructorFixture() {
        this(0);
    }

    public int value() {
        return value;
    }

    public int doubled() {
        return value * 2;
    }
}
