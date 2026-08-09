package com.meguri.core.react;

import java.time.Instant;

/** Sanitized round trace. Action arguments and raw tool results are intentionally absent. */
public record ReactRoundTrace(
        String turnId,
        int roundIndex,
        String plannerRevision,
        ReactDecision decision,
        String goal,
        String reasonCode,
        String actionDigest,
        String capabilityId,
        String observationSummary,
        String observationDigest,
        boolean reusedObservation,
        Boolean actionSuccessful,
        ReactTerminationReason terminationReason,
        Instant startedAt,
        Instant completedAt) {
}
