package com.meguri.core.react;

import java.util.LinkedHashSet;
import java.util.Set;

/** Identity and frozen-snapshot scope forwarded to the existing Capability Runtime. */
public record ReactInvocationScope(
        String turnId,
        String traceId,
        String tenantId,
        String userId,
        String clientId,
        Set<String> scopes,
        String capabilitySnapshotVersion,
        boolean networkAllowed) {

    public ReactInvocationScope {
        turnId = ReactValues.required(turnId, "turnId");
        traceId = ReactValues.required(traceId, "traceId");
        tenantId = ReactValues.required(tenantId, "tenantId");
        userId = ReactValues.required(userId, "userId");
        clientId = ReactValues.required(clientId, "clientId");
        capabilitySnapshotVersion = ReactValues.required(
                capabilitySnapshotVersion, "capabilitySnapshotVersion");
        scopes = Set.copyOf(scopes == null ? Set.of() : new LinkedHashSet<>(scopes));
    }
}
