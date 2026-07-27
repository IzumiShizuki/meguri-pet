package com.meguri.core.harness.capability;

import java.util.Map;
import java.util.Set;

/** Small deterministic schema used before a tool implementation is invoked. */
public record CapabilityInputSchema(
        Map<String, ValueKind> properties,
        Set<String> required,
        boolean additionalProperties) {
    public CapabilityInputSchema {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        required = required == null ? Set.of() : Set.copyOf(required);
        if (!properties.keySet().containsAll(required)) {
            throw new IllegalArgumentException("required schema fields must be declared properties");
        }
    }

    public void validate(Map<String, Object> input) {
        Map<String, Object> value = input == null ? Map.of() : input;
        for (String field : required) {
            if (!value.containsKey(field) || value.get(field) == null) {
                throw new CapabilityExecutionException("missing required capability input: " + field);
            }
        }
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            ValueKind kind = properties.get(entry.getKey());
            if (kind == null) {
                if (!additionalProperties) {
                    throw new CapabilityExecutionException("unknown capability input: " + entry.getKey());
                }
                continue;
            }
            if (entry.getValue() != null && !kind.accepts(entry.getValue())) {
                throw new CapabilityExecutionException(
                        "invalid capability input type for " + entry.getKey() + ": expected " + kind);
            }
        }
    }

    public enum ValueKind {
        STRING, NUMBER, BOOLEAN, OBJECT, ARRAY;

        boolean accepts(Object value) {
            return switch (this) {
                case STRING -> value instanceof String;
                case NUMBER -> value instanceof Number number && isFinite(number);
                case BOOLEAN -> value instanceof Boolean;
                case OBJECT -> value instanceof Map<?, ?>;
                case ARRAY -> value instanceof Iterable<?> || value.getClass().isArray();
            };
        }

        private static boolean isFinite(Number value) {
            if (value instanceof Double number) return Double.isFinite(number);
            if (value instanceof Float number) return Float.isFinite(number);
            return true;
        }
    }
}
