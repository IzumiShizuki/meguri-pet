package com.meguri.core.retrieval;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public record RetrievalContext(
        String principalId,
        Set<String> aclScopes,
        String snapshotId,
        long revision,
        Instant validAt,
        Instant deadline,
        String traceId,
        String tenantId) {
    public RetrievalContext(
            String principalId,
            Set<String> aclScopes,
            String snapshotId,
            long revision,
            Instant validAt,
            Instant deadline,
            String traceId) {
        this(principalId, aclScopes, snapshotId, revision, validAt, deadline,
                traceId, "meguri-local");
    }

    public RetrievalContext {
        if (blank(principalId) || blank(snapshotId) || revision < 0
                || blank(traceId) || blank(tenantId)) {
            throw new IllegalArgumentException(
                    "principal, snapshot, revision, traceId and tenantId are required");
        }
        aclScopes = aclScopes == null ? Set.of() : Set.copyOf(aclScopes);
        validAt = validAt == null ? Instant.now() : validAt;
        deadline = deadline == null ? validAt : deadline;
        tenantId = tenantId.trim();
    }

    public Duration remaining() {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() || remaining.isZero()
                ? Duration.ofMillis(1) : remaining;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
