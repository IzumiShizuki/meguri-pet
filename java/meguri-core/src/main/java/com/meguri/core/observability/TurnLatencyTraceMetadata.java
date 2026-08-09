package com.meguri.core.observability;

import java.util.Set;

/** Version and grouping dimensions fixed for the lifetime of one Turn trace. */
public record TurnLatencyTraceMetadata(
        String traceId,
        String turnId,
        String releaseId,
        String gitCommit,
        String imageDigest,
        String responseContractRevision,
        String promptRevision,
        String model,
        String provider,
        String executionMode,
        String retrievalMode,
        String clientType) {

    private static final Set<String> EXECUTION_MODES = Set.of("FAST", "THINK", "AGENT");
    private static final Set<String> RETRIEVAL_MODES = Set.of("NONE", "FAST", "SLOW");

    public TurnLatencyTraceMetadata {
        traceId = required(traceId, "traceId");
        turnId = required(turnId, "turnId");
        releaseId = dimension(releaseId);
        gitCommit = dimension(gitCommit);
        imageDigest = dimension(imageDigest);
        responseContractRevision = dimension(responseContractRevision);
        promptRevision = dimension(promptRevision);
        model = dimension(model);
        provider = dimension(provider);
        executionMode = mode(executionMode, "executionMode", EXECUTION_MODES);
        retrievalMode = mode(retrievalMode, "retrievalMode", RETRIEVAL_MODES);
        clientType = dimension(clientType);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String dimension(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String mode(String value, String field, Set<String> allowed) {
        String normalized = required(value, field).toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("unsupported " + field + ": " + value);
        }
        return normalized;
    }
}
