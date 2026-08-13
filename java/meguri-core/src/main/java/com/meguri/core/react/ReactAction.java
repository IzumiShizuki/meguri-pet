package com.meguri.core.react;

import java.util.LinkedHashMap;
import java.util.Map;

public record ReactAction(
        String capabilityId,
        Map<String, Object> arguments,
        String operationId,
        String idempotencyKey,
        String approvalId) {
    public ReactAction {
        capabilityId = ReactValues.required(capabilityId, "capabilityId");
        arguments = ReactValues.immutableMap(arguments);
        operationId = ReactValues.optional(operationId);
        idempotencyKey = ReactValues.optional(idempotencyKey);
        approvalId = ReactValues.optional(approvalId);
    }

    public ReactAction(String capabilityId, Map<String, Object> arguments) {
        this(capabilityId, arguments, null, null, null);
    }

    public String digest() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("capability_id", capabilityId);
        value.put("arguments", arguments);
        if (operationId != null) value.put("operation_id", operationId);
        if (idempotencyKey != null) value.put("idempotency_key", idempotencyKey);
        if (approvalId != null) value.put("approval_id", approvalId);
        return ActionDigest.sha256(value);
    }
}
