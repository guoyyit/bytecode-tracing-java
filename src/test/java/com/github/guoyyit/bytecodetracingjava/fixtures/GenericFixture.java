package com.github.guoyyit.bytecodetracingjava.fixtures;

import java.util.ArrayList;
import java.util.List;

/** Generic signatures + local variables preservation scenario. */
public class GenericFixture {

    public static <T extends Number> T identity(T value) {
        return value;
    }

    public static List<String> copyNames(List<String> input) {
        List<String> copy = new ArrayList<>(input);
        int size = copy.size();
        return size == 0 ? copy : copy.subList(0, size);
    }
}
