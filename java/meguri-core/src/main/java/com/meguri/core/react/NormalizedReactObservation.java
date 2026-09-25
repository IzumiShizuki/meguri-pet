package com.meguri.core.react;

import java.util.Objects;

/** Safe observation persisted and returned to the planner. */
public record NormalizedReactObservation(
        RawReactObservation.Status status,
        String summary,
        String informationDigest,
        String errorCode,
        ObservationTrustLabel trustLabel,
        boolean retryable,
        boolean evidenceSufficient,
        long tokensUsed,
        long costUnits,
        boolean reused) {

    public NormalizedReactObservation {
        status = Objects.requireNonNull(status, "status");
        summary = summary == null ? "" : summary;
        informationDigest = ReactValues.required(informationDigest, "informationDigest");
        errorCode = ReactValues.optional(errorCode);
        trustLabel = Objects.requireNonNull(trustLabel, "trustLabel");
        if (tokensUsed < 0 || costUnits < 0) {
            throw new IllegalArgumentException("observation usage must be non-negative");
        }
    }

    public NormalizedReactObservation asReused() {
        return new NormalizedReactObservation(status, summary, informationDigest,
                errorCode, trustLabel, retryable, evidenceSufficient, 0, 0, true);
    }

    public boolean successful() {
        return status == RawReactObservation.Status.SUCCESS;
    }
}
