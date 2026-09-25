package com.meguri.core.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Configuration-shaped limits used to derive an absolute per-Turn budget. */
public record ExecutionBudgetLimits(
        int maxPreProviderStages,
        int maxModelCalls,
        int maxRetrievalCalls,
        int maxToolCalls,
        int maxRemoteAgents,
        int maxAgentDepth,
        int maxRounds,
        int repeatedActionLimit,
        long maxTokens,
        long maxCostUnits,
        Duration maximumDuration) {

    public ExecutionBudgetLimits {
        // Reuse the canonical validation without inventing a second set of rules.
        new ExecutionBudget(maxPreProviderStages, maxModelCalls, maxRetrievalCalls,
                maxToolCalls, maxRemoteAgents, maxAgentDepth, maxRounds,
                repeatedActionLimit, maxTokens, maxCostUnits, Instant.EPOCH);
        maximumDuration = Objects.requireNonNull(maximumDuration, "maximumDuration");
        if (maximumDuration.isNegative() || maximumDuration.isZero()) {
            throw new IllegalArgumentException("maximumDuration must be positive");
        }
    }

    ExecutionBudget at(Instant now, Instant parentDeadline) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(parentDeadline, "parentDeadline");
        Instant durationDeadline;
        try {
            durationDeadline = now.plus(maximumDuration);
        } catch (RuntimeException overflow) {
            durationDeadline = Instant.MAX;
        }
        Instant deadline = durationDeadline.isBefore(parentDeadline)
                ? durationDeadline : parentDeadline;
        return new ExecutionBudget(maxPreProviderStages, maxModelCalls,
                maxRetrievalCalls, maxToolCalls, maxRemoteAgents, maxAgentDepth,
                maxRounds, repeatedActionLimit, maxTokens, maxCostUnits, deadline);
    }
}
