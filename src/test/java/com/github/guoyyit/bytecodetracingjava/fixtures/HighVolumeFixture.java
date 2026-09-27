package com.github.guoyyit.bytecodetracingjava.fixtures;

/** High-call-volume scenario: a tiny method invoked in a tight loop. */
public class HighVolumeFixture {

    public static int tiny(int x) {
        return x + 1;
    }

    public static long loop(int iterations) {
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            acc += tiny(i);
        }
        return acc;
    }
}
