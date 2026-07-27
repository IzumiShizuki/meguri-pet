package com.meguri.core.harness;

import java.time.Instant;
import java.util.List;

/** Immutable versions and grants frozen for the lifetime of a turn. */
public record HarnessManifest(
        String protocolVersion,
        String buildId,
        String contextRevision,
        String personaRevision,
        String capabilitySnapshotVersion,
        List<String> grantedCapabilities,
        Instant frozenAt) {
    public HarnessManifest {
        protocolVersion = required(protocolVersion, "protocol_version");
        buildId = required(buildId, "build_id");
        contextRevision = required(contextRevision, "context_revision");
        personaRevision = required(personaRevision, "persona_revision");
        capabilitySnapshotVersion = required(capabilitySnapshotVersion, "capability_snapshot_version");
        grantedCapabilities = grantedCapabilities == null ? List.of() : List.copyOf(grantedCapabilities);
        frozenAt = frozenAt == null ? Instant.now() : frozenAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
