package com.meguri.core.react;

import java.util.List;
import java.util.Objects;

public record ReactRunResult(
        String turnId,
        ReactTerminationReason terminationReason,
        ReactDecision finalDecision,
        String finalAnswer,
        int rounds,
        int modelCalls,
        int toolCalls,
        long tokensUsed,
        long costUnitsUsed,
        List<NormalizedReactObservation> observations) {

    public ReactRunResult {
        turnId = ReactValues.required(turnId, "turnId");
        terminationReason = Objects.requireNonNull(terminationReason, "terminationReason");
        finalDecision = Objects.requireNonNull(finalDecision, "finalDecision");
        finalAnswer = ReactValues.optional(finalAnswer);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
