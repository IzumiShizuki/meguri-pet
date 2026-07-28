package com.meguri.core.capability;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class McpCapabilityNormalizer {
    private static final Set<String> METADATA_KEYS = Set.of(
            "name", "title", "description", "inputSchema", "outputSchema",
            "annotations", "timeout_ms", "concurrency_limit");

    public CapabilityDescriptor normalize(String server, Map<String, Object> tool, int protocolVersion) {
        String serverId = identifier(server, "server");
        rejectSecretMetadata(tool);
        for (String key : tool.keySet()) {
            if (!METADATA_KEYS.contains(key)) throw new IllegalArgumentException("unsupported MCP metadata: " + key);
        }
        String name = identifier(String.valueOf(tool.get("name")), "tool.name");
        Map<String, Object> annotations = object(tool.get("annotations"), "annotations", true);
        boolean readOnly = bool(annotations, "readOnlyHint", false);
        boolean destructive = bool(annotations, "destructiveHint", false);
        if (readOnly && destructive) throw new IllegalArgumentException("conflicting MCP risk hints");

        // Remote declarations are hints only: unknown/open-world tools retain the local write-risk floor.
        CapabilityDescriptor.Kind kind = readOnly && !destructive
                ? CapabilityDescriptor.Kind.READ_TOOL : CapabilityDescriptor.Kind.WRITE_TOOL;
        CapabilityDescriptor.SideEffect effect = kind == CapabilityDescriptor.Kind.READ_TOOL
                ? CapabilityDescriptor.SideEffect.EXTERNAL : CapabilityDescriptor.SideEffect.WRITE;
        CapabilityDescriptor.ApprovalRequirement approval = kind == CapabilityDescriptor.Kind.WRITE_TOOL
                ? CapabilityDescriptor.ApprovalRequirement.ALWAYS : CapabilityDescriptor.ApprovalRequirement.RISK_BASED;
        int attempts = kind == CapabilityDescriptor.Kind.READ_TOOL ? 2 : 1;

        return new CapabilityDescriptor(
                "mcp." + serverId + "." + name,
                "mcp-p" + protocolVersion + "-" + fingerprint(tool),
                kind,
                "mcp:" + serverId,
                schema(tool.get("inputSchema"), "inputSchema"),
                schema(tool.get("outputSchema"), "outputSchema"),
                Set.of("mcp:" + serverId),
                effect,
                approval,
                Duration.ofMillis(number(tool, "timeout_ms", 5_000, 100, 300_000)),
                new CapabilityDescriptor.RetryPolicy(attempts, Duration.ofMillis(25)),
                new CapabilityDescriptor.ConcurrencyPolicy((int) number(tool, "concurrency_limit", 1, 1, 32)),
                Set.of(CapabilityDescriptor.Mode.BALANCED, CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL,
                "mcp://" + serverId + "/" + name,
                CapabilityDescriptor.Health.HEALTHY,
                false,
                protocolVersion,
                new CapabilityDescriptor.NetworkPolicy(true, Set.of(serverId)),
                Set.of(),
                new CapabilityDescriptor.CostPolicy(1, 100),
                CapabilityDescriptor.CachePolicy.disabled(),
                kind == CapabilityDescriptor.Kind.WRITE_TOOL
                        ? new CapabilityDescriptor.IdempotencyPolicy(false, false)
                        : CapabilityDescriptor.IdempotencyPolicy.none());
    }

    private static CapabilityDescriptor.Schema schema(Object raw, String field) {
        if (raw == null && field.equals("outputSchema")) return CapabilityDescriptor.Schema.empty();
        Map<String, Object> schema = object(raw, field, false);
        if (!"object".equals(schema.get("type"))) throw new IllegalArgumentException(field + " must be object schema");
        Map<String, Object> rawProperties = object(schema.get("properties"), field + ".properties", true);
        Map<String, CapabilityDescriptor.ValueType> properties = new LinkedHashMap<>();
        rawProperties.forEach((name, value) -> {
            Map<String, Object> property = object(value, "property", false);
            properties.put(name, valueType(property.get("type")));
        });
        Set<String> required = new LinkedHashSet<>();
        if (schema.get("required") instanceof Iterable<?> values) {
            values.forEach(value -> required.add(CapabilityDescriptor.required(String.valueOf(value), "required")));
        } else if (schema.containsKey("required")) {
            throw new IllegalArgumentException(field + ".required must be an array");
        }
        boolean additional = Boolean.TRUE.equals(schema.get("additionalProperties"));
        return new CapabilityDescriptor.Schema(properties, required, additional, Set.of());
    }

    private static CapabilityDescriptor.ValueType valueType(Object raw) {
        return switch (String.valueOf(raw).toLowerCase(Locale.ROOT)) {
            case "string" -> CapabilityDescriptor.ValueType.STRING;
            case "number", "integer" -> CapabilityDescriptor.ValueType.NUMBER;
            case "boolean" -> CapabilityDescriptor.ValueType.BOOLEAN;
            case "object" -> CapabilityDescriptor.ValueType.OBJECT;
            case "array" -> CapabilityDescriptor.ValueType.ARRAY;
            default -> throw new IllegalArgumentException("unsupported MCP schema type: " + raw);
        };
    }

    private static void rejectSecretMetadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String name = String.valueOf(key).toLowerCase(Locale.ROOT);
                if (name.matches(".*(secret|password|credential|api.?key|access.?token).*")) {
                    throw new IllegalArgumentException("secret material is forbidden in MCP metadata");
                }
                rejectSecretMetadata(nested);
            });
        } else if (value instanceof Iterable<?> values) {
            values.forEach(McpCapabilityNormalizer::rejectSecretMetadata);
        }
    }

    private static String identifier(String value, String field) {
        String result = CapabilityDescriptor.required(value, field).toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException(field + " is unsafe");
        return result;
    }

    private static Map<String, Object> object(Object value, String field, boolean optional) {
        if (value == null && optional) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(field + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, nested) -> result.put(String.valueOf(key), nested));
        return result;
    }

    private static boolean bool(Map<String, Object> source, String key, boolean fallback) {
        if (!source.containsKey(key)) return fallback;
        if (!(source.get(key) instanceof Boolean value)) throw new IllegalArgumentException(key + " must be boolean");
        return value;
    }

    private static long number(Map<String, Object> source, String key, long fallback, long min, long max) {
        if (!source.containsKey(key)) return fallback;
        if (!(source.get(key) instanceof Number number)) throw new IllegalArgumentException(key + " must be numeric");
        long value = number.longValue();
        if (value < min || value > max) throw new IllegalArgumentException(key + " is outside local bounds");
        return value;
    }

    private static String fingerprint(Map<String, Object> tool) {
        Map<String, Object> securityMetadata = new LinkedHashMap<>(tool);
        // Human-facing remote text is untrusted and cannot change execution identity or policy.
        securityMetadata.remove("title");
        securityMetadata.remove("description");
        return CapabilityDigest.sha256(securityMetadata).substring(0, 12);
    }
}
