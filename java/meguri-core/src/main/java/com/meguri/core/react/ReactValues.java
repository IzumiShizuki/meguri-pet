package com.meguri.core.react;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReactValues {
    private ReactValues() {
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(required(key, "object key"), immutable(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("object key must be a string");
                }
                copy.put(required(text, "object key"), immutable(nested));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(immutable(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            ArrayList<Object> copy = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                copy.add(immutable(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
