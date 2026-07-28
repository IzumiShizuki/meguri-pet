package com.meguri.core.capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ToolProposal(
        String turnId,
        String traceId,
        String tenantId,
        String userId,
        String clientId,
        String capabilityId,
        Map<String, Object> input,
        Set<String> scopes,
        String operationId,
        String idempotencyKey,
        String approvalId,
        boolean networkAllowed,
        long maximumCostUnits) {
    public ToolProposal {
        turnId = CapabilityDescriptor.required(turnId, "turnId");
        traceId = CapabilityDescriptor.required(traceId, "traceId");
        tenantId = CapabilityDescriptor.required(tenantId, "tenantId");
        userId = CapabilityDescriptor.required(userId, "userId");
        clientId = CapabilityDescriptor.required(clientId, "clientId");
        capabilityId = CapabilityDescriptor.required(capabilityId, "capabilityId");
        input = immutableMap(input);
        scopes = CapabilityDescriptor.strings(scopes, "scopes");
        operationId = optional(operationId);
        idempotencyKey = optional(idempotencyKey);
        approvalId = optional(approvalId);
        if (maximumCostUnits < 0) throw new IllegalArgumentException("maximumCostUnits must not be negative");
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(CapabilityDescriptor.required(key, "input key"), immutable(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("object key must be a string");
                copy.put(text, immutable(nested));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> copy = new java.util.ArrayList<>();
            iterable.forEach(item -> copy.add(immutable(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
