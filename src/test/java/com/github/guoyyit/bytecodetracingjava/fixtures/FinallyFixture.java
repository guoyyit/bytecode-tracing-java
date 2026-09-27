package com.github.guoyyit.bytecodetracingjava.fixtures;

/** Finally / multiple-return scenarios: each call must be counted exactly once. */
public class FinallyFixture {

    public static int withFinally(boolean fail) {
        try {
            if (fail) {
                throw new IllegalStateException("fail");
            }
            return 1;
        } finally {
            // ordinary finally block
            int ignored = 0;
            for (int i = 0; i < 3; i++) {
                ignored += i;
            }
        }
    }

    public static int multiReturn(int x) {
        if (x < 0) {
            return -1;
        }
        if (x == 0) {
            return 0;
        }
        if (x % 2 == 0) {
            return x / 2;
        }
        return x * 3 + 1;
    }

    public static int tryWithResources() throws Exception {
        try (AutoCloseable c = () -> { }) {
            return 7;
        }
    }

    public static int nestedTry(int x) {
        try {
            try {
                if (x < 0) {
                    throw new IllegalArgumentException();
                }
                return x;
            } finally {
                int a = 1;
            }
        } catch (IllegalArgumentException e) {
            return -1;
        } finally {
            int b = 2;
        }
    }
}
