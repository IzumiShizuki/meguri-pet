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
        List<NormalizedReactObservation> observations,
        List<ReactCapability> exposedCapabilities,
        List<ReactSkillCandidate> skillCandidates) {

    public ReactPlanningContext {
        observations = observations == null ? List.of() : List.copyOf(observations);
        exposedCapabilities = exposedCapabilities == null
                ? List.of() : List.copyOf(exposedCapabilities);
        skillCandidates = skillCandidates == null
                ? List.of() : List.copyOf(skillCandidates);
    }

    /** Compatibility constructor for v1 callers without external Skill L1 data. */
    public ReactPlanningContext(
            ReactInvocationScope scope,
            String goal,
            int roundIndex,
            int remainingModelCalls,
            int remainingToolCalls,
            long remainingTokens,
            long remainingCostUnits,
            Instant deadlineAt,
            List<NormalizedReactObservation> observations,
            List<ReactCapability> exposedCapabilities) {
        this(scope, goal, roundIndex, remainingModelCalls, remainingToolCalls,
                remainingTokens, remainingCostUnits, deadlineAt, observations,
                exposedCapabilities, List.of());
    }

    /** Compatibility constructor for embedders using the original v1 context. */
    public ReactPlanningContext(
            ReactInvocationScope scope,
            String goal,
            int roundIndex,
            int remainingModelCalls,
            int remainingToolCalls,
            long remainingTokens,
            long remainingCostUnits,
            Instant deadlineAt,
            List<NormalizedReactObservation> observations) {
        this(scope, goal, roundIndex, remainingModelCalls, remainingToolCalls,
                remainingTokens, remainingCostUnits, deadlineAt, observations,
                List.of(), List.of());
    }
}
