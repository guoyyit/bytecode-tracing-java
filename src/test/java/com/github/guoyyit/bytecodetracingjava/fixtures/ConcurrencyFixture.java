package com.github.guoyyit.bytecodetracingjava.fixtures;

/** Concurrency scenario: a pure function hammered from many threads. */
public class ConcurrencyFixture {

    public static long work(long seed, int rounds) {
        long x = seed;
        for (int i = 0; i < rounds; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            x ^= x >>> 33;
        }
        return x;
    }

    public static long nested(long seed, int rounds) {
        long inner = work(seed, rounds);
        return work(inner, rounds / 2 + 1);
    }
}
