package com.meguri.core.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable server-owned ceilings for one Turn. Child work may only narrow it.
 */
public record ExecutionBudget(
        @JsonProperty("max_pre_provider_stages") int maxPreProviderStages,
        @JsonProperty("max_model_calls") int maxModelCalls,
        @JsonProperty("max_retrieval_calls") int maxRetrievalCalls,
        @JsonProperty("max_tool_calls") int maxToolCalls,
        @JsonProperty("max_remote_agents") int maxRemoteAgents,
        @JsonProperty("max_agent_depth") int maxAgentDepth,
        @JsonProperty("max_rounds") int maxRounds,
        @JsonProperty("repeated_action_limit") int repeatedActionLimit,
        @JsonProperty("max_tokens") long maxTokens,
        @JsonProperty("max_cost_units") long maxCostUnits,
        @JsonProperty("deadline_at")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant deadlineAt) {

    public ExecutionBudget {
        if (maxPreProviderStages < 0 || maxModelCalls < 0 || maxRetrievalCalls < 0
                || maxToolCalls < 0 || maxRemoteAgents < 0 || maxAgentDepth < 0
                || maxRounds < 0 || repeatedActionLimit < 0 || maxTokens < 0
                || maxCostUnits < 0) {
            throw new IllegalArgumentException("execution budget values must be non-negative");
        }
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    }

    /** Returns the component-wise intersection; it can never expand either budget. */
    public ExecutionBudget clipTo(ExecutionBudget ceiling) {
        Objects.requireNonNull(ceiling, "ceiling");
        return new ExecutionBudget(
                Math.min(maxPreProviderStages, ceiling.maxPreProviderStages),
                Math.min(maxModelCalls, ceiling.maxModelCalls),
                Math.min(maxRetrievalCalls, ceiling.maxRetrievalCalls),
                Math.min(maxToolCalls, ceiling.maxToolCalls),
                Math.min(maxRemoteAgents, ceiling.maxRemoteAgents),
                Math.min(maxAgentDepth, ceiling.maxAgentDepth),
                Math.min(maxRounds, ceiling.maxRounds),
                Math.min(repeatedActionLimit, ceiling.repeatedActionLimit),
                Math.min(maxTokens, ceiling.maxTokens),
                Math.min(maxCostUnits, ceiling.maxCostUnits),
                deadlineAt.isBefore(ceiling.deadlineAt) ? deadlineAt : ceiling.deadlineAt);
    }

    public long remainingMillis(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        long remaining;
        try {
            remaining = Math.subtractExact(
                    deadlineAt.toEpochMilli(), clock.instant().toEpochMilli());
        } catch (ArithmeticException overflow) {
            return deadlineAt.isAfter(clock.instant()) ? Long.MAX_VALUE : 0L;
        }
        return Math.max(0L, remaining);
    }

    public boolean expired(Clock clock) {
        return remainingMillis(clock) == 0L;
    }

    /** FAST and THINK do not carry an action/agent loop in contract revision v1. */
    public ExecutionBudget withoutAgentLoop() {
        return new ExecutionBudget(
                maxPreProviderStages,
                maxModelCalls,
                maxRetrievalCalls,
                0,
                0,
                0,
                0,
                0,
                maxTokens,
                maxCostUnits,
                deadlineAt);
    }

    /** Limited ReAct v1 is hard-bounded to six decisions and four actions. */
    public ExecutionBudget limitedReactV1() {
        return new ExecutionBudget(
                maxPreProviderStages,
                Math.min(maxModelCalls, 6),
                maxRetrievalCalls,
                Math.min(maxToolCalls, 4),
                maxRemoteAgents,
                maxAgentDepth,
                Math.min(maxRounds, 6),
                repeatedActionLimit,
                maxTokens,
                maxCostUnits,
                deadlineAt);
    }
}
