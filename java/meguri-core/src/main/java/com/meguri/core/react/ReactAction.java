package com.meguri.core.react;

import java.util.Map;

public record ReactAction(String capabilityId, Map<String, Object> arguments) {
    public ReactAction {
        capabilityId = ReactValues.required(capabilityId, "capabilityId");
        arguments = ReactValues.immutableMap(arguments);
    }

    public String digest() {
        return ActionDigest.sha256(Map.of(
                "capability_id", capabilityId,
                "arguments", arguments));
    }
}
