package com.meguri.core.harness.capability;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts MCP tool metadata into the same fail-closed capability contract. */
public final class McpCapabilityNormalizer {
    private static final long MIN_TIMEOUT_MS = 100L;
    private static final long MAX_TIMEOUT_MS = 300_000L;
    private static final int MAX_CONCURRENCY = 32;

    private McpCapabilityNormalizer() { }

    public static Normalized normalize(String serverName, Map<String, Object> tool) {
        String server = identifier(serverName, "serverName");
        String toolName = identifier(string(tool.get("name")), "tool.name");
        Map<String, Object> annotations = optionalObjectMap(tool, "annotations");
        boolean readOnly = annotation(annotations, "readOnlyHint", false);
        boolean destructive = annotation(annotations, "destructiveHint", false);
        annotation(annotations, "openWorldHint", true);
        if (readOnly && destructive) {
            throw new IllegalArgumentException("MCP tool annotations conflict: readOnlyHint and destructiveHint");
        }

        CapabilityRegistry.Effect effect = destructive
                ? CapabilityRegistry.Effect.WRITE : CapabilityRegistry.Effect.EXTERNAL;
        // MCP annotations are untrusted hints and may never waive Meguri's local approval gate.
        CapabilityRegistry.Approval approval = destructive
                ? CapabilityRegistry.Approval.ALWAYS : CapabilityRegistry.Approval.RISK_BASED;
        CapabilityRegistry.Kind kind = CapabilityRegistry.Kind.WRITE_TOOL;
        long timeoutMs = boundedNumber(tool, "timeout_ms", 5_000L, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        int concurrency = (int) boundedNumber(tool, "concurrency_limit", 1L, 1L, MAX_CONCURRENCY);
        CapabilityRegistry.Descriptor descriptor = new CapabilityRegistry.Descriptor(
                "mcp." + server + "." + toolName,
                kind,
                effect,
                approval,
                Duration.ofMillis(timeoutMs),
                concurrency,
                "mcp://" + server + "/" + toolName);
        Object rawInputSchema = tool.get("inputSchema");
        if (!(rawInputSchema instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("MCP tool inputSchema must be an object schema");
        }
        return new Normalized(descriptor, inputSchema(requiredObjectMap(rawInputSchema, "inputSchema")));
    }

    private static CapabilityInputSchema inputSchema(Map<String, Object> schema) {
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("MCP tool inputSchema type must be object");
        }
        rejectUnsupported(schema, Set.of(
                "type", "properties", "required", "additionalProperties", "title", "description", "$schema"),
                "inputSchema");
        Map<String, Object> rawProperties = schema.containsKey("properties")
                ? requiredObjectMap(schema.get("properties"), "inputSchema.properties") : Map.of();
        Map<String, CapabilityInputSchema.ValueKind> properties = new LinkedHashMap<>();
        rawProperties.forEach((name, raw) -> {
            if (name.isBlank()) throw new IllegalArgumentException("MCP schema property name must not be blank");
            Map<String, Object> property = requiredObjectMap(raw, "property " + name);
            rejectUnsupported(property, Set.of("type", "title", "description"), "property " + name);
            properties.put(name, kind(property.get("type")));
        });
        Set<String> required = new LinkedHashSet<>();
        Object rawRequired = schema.get("required");
        if (rawRequired != null) {
            if (!(rawRequired instanceof Iterable<?> values)) {
                throw new IllegalArgumentException("MCP tool inputSchema.required must be an array");
            }
            values.forEach(value -> {
                if (!(value instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("MCP required property names must be non-blank strings");
                }
                required.add(text);
            });
        }
        Object rawAdditional = schema.get("additionalProperties");
        if (rawAdditional != null && !(rawAdditional instanceof Boolean)) {
            throw new IllegalArgumentException("MCP tool inputSchema.additionalProperties must be boolean");
        }
        boolean additional = Boolean.TRUE.equals(rawAdditional);
        return new CapabilityInputSchema(properties, required, additional);
    }

    private static CapabilityInputSchema.ValueKind kind(Object value) {
        return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "number", "integer" -> CapabilityInputSchema.ValueKind.NUMBER;
            case "boolean" -> CapabilityInputSchema.ValueKind.BOOLEAN;
            case "object" -> CapabilityInputSchema.ValueKind.OBJECT;
            case "array" -> CapabilityInputSchema.ValueKind.ARRAY;
            case "string" -> CapabilityInputSchema.ValueKind.STRING;
            default -> throw new IllegalArgumentException("unsupported MCP schema type: " + value);
        };
    }

    private static void rejectUnsupported(Map<String, Object> value, Set<String> supported, String scope) {
        for (String key : value.keySet()) {
            if (!supported.contains(key)) {
                throw new IllegalArgumentException("unsupported MCP schema keyword in " + scope + ": " + key);
            }
        }
    }

    private static String identifier(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(field + " contains unsafe identifier characters");
        }
        return normalized;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean annotation(Map<String, Object> annotations, String key, boolean fallback) {
        if (!annotations.containsKey(key)) return fallback;
        Object value = annotations.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("MCP annotation " + key + " must be boolean");
        }
        return flag;
    }

    private static long boundedNumber(
            Map<String, Object> source, String key, long fallback, long minimum, long maximum) {
        if (!source.containsKey(key)) return fallback;
        Object value = source.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("MCP " + key + " must be numeric");
        }
        long parsed;
        try {
            parsed = new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("MCP " + key + " must be an integer", invalid);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    "MCP " + key + " must be within " + minimum + ".." + maximum);
        }
        return parsed;
    }

    private static Map<String, Object> optionalObjectMap(Map<String, Object> source, String key) {
        if (!source.containsKey(key)) return Map.of();
        return requiredObjectMap(source.get(key), key);
    }

    private static Map<String, Object> requiredObjectMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("MCP " + field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("MCP " + field + " keys must be strings");
            }
            result.put(text, item);
        });
        return result;
    }

    public record Normalized(
            CapabilityRegistry.Descriptor descriptor,
            CapabilityInputSchema inputSchema) { }
}
