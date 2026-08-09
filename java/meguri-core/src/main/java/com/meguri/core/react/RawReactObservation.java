package com.meguri.core.react;

import java.util.Map;
import java.util.Objects;

/** Transient capability output. It must be normalized before trace persistence. */
public record RawReactObservation(
        Status status,
        Map<String, Object> data,
        String summary,
        String errorCode,
        ObservationTrustLabel trustLabel,
        boolean retryable,
        boolean evidenceSufficient,
        long tokensUsed,
        long costUnits) {

    public enum Status { SUCCESS, FAILED }

    public RawReactObservation {
        status = Objects.requireNonNull(status, "status");
        data = ReactValues.immutableMap(data);
        summary = ReactValues.optional(summary);
        errorCode = ReactValues.optional(errorCode);
        trustLabel = Objects.requireNonNull(trustLabel, "trustLabel");
        if (tokensUsed < 0 || costUnits < 0) {
            throw new IllegalArgumentException("observation usage must be non-negative");
        }
        if (status == Status.FAILED && errorCode == null) {
            throw new IllegalArgumentException("failed observation requires errorCode");
        }
    }

    public static RawReactObservation executionFailure() {
        return new RawReactObservation(
                Status.FAILED, Map.of(), "Capability execution failed",
                "ACTION_EXECUTION_FAILED",
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                false, false, 0, 0);
    }
}
