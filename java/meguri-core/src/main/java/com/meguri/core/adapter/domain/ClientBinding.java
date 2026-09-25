package com.meguri.core.adapter.domain;

import java.time.Instant;

public record ClientBinding(
        String clientInstanceId,
        String tenantId,
        String meguriUserId,
        String clientId,
        String platformActorHash,
        String clientVersion,
        String selectedProtocolVersion,
        String serverCapabilitiesRevision,
        AdapterClientCapabilities capabilities,
        ClientPermissions permissions,
        Instant lastSeenAt) {
    public ClientBinding {
        clientInstanceId = required(clientInstanceId, "clientInstanceId");
        tenantId = required(tenantId, "tenantId");
        meguriUserId = required(meguriUserId, "meguriUserId");
        clientId = required(clientId, "clientId");
        platformActorHash = optionalHash(platformActorHash);
        clientVersion = required(clientVersion, "clientVersion");
        selectedProtocolVersion = required(selectedProtocolVersion, "selectedProtocolVersion");
        serverCapabilitiesRevision = required(serverCapabilitiesRevision, "serverCapabilitiesRevision");
        if (capabilities == null || permissions == null) {
            throw new IllegalArgumentException("binding capabilities and permissions must be present");
        }
        lastSeenAt = lastSeenAt == null ? Instant.now() : lastSeenAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalHash(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "platformActorHash must be a SHA-256 hexadecimal value");
        }
        return normalized;
    }
}
