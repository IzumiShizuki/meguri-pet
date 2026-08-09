package com.meguri.core.react;

import java.time.Instant;
import java.util.List;

/** Sanitized planner context; it contains observations, not private reasoning. */
public record ReactPlanningContext(
        ReactInvocationScope scope,
        String goal,
        int roundIndex,
        int remainingModelCalls,
        int remainingToolCalls,
        long remainingTokens,
        long remainingCostUnits,
        Instant deadlineAt,
        List<NormalizedReactObservation> observations) {

    public ReactPlanningContext {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
