package com.github.guoyyit.bytecodetracingjava.tracing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assigns stable small integer ids to instrumented methods so that
 * instrumented bytecode only carries an int constant. Idempotent per
 * (class, name, descriptor) triple and bounded in size.
 */
public class MethodRegistry {

    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, MethodDescriptor> byId = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final int maxEntries;

    public MethodRegistry() {
        this(100_000);
    }

    public MethodRegistry(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * @return the id for the given method, registering it if necessary
     * @throws IllegalStateException if the registry is full
     */
    public int register(String className, String methodName, String descriptor) {
        String key = className + '#' + methodName + descriptor;
        Integer existing = ids.get(key);
        if (existing != null) {
            return existing;
        }
        return ids.computeIfAbsent(key, k -> {
            int id = nextId.getAndIncrement();
            if (id >= maxEntries) {
                throw new IllegalStateException(
                        "method registry full (" + maxEntries + " entries); refusing " + k);
            }
            byId.put(id, new MethodDescriptor(id, className, methodName, descriptor));
            return id;
        });
    }

    public Optional<MethodDescriptor> lookup(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<MethodDescriptor> all() {
        List<MethodDescriptor> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparingInt(MethodDescriptor::id));
        return all;
    }

    public int size() {
        return ids.size();
    }
}
