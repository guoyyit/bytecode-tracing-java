package com.github.guoyyit.bytecodetracingjava.fixtures;

/** Recursion scenarios: self-recursion and mutual recursion. */
public class RecursiveFixture {

    public static long fib(int n) {
        if (n < 2) {
            return n;
        }
        return fib(n - 1) + fib(n - 2);
    }

    public static long factorial(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * factorial(n - 1);
    }

    public static int mutualA(int n) {
        if (n <= 0) {
            return 0;
        }
        return 1 + mutualB(n - 1);
    }

    public static int mutualB(int n) {
        if (n <= 0) {
            return 0;
        }
        return 1 + mutualA(n - 1);
    }

    /** Infinite recursion for StackOverflowError unwinding tests. */
    public static int infinite() {
        return infinite() + 1;
    }
}
