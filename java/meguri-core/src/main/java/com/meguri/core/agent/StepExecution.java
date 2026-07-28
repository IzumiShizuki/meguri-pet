package com.meguri.core.agent;

import java.time.Instant;
import java.util.Objects;

public record StepExecution(
        String stepExecutionId,
        String executionId,
        String stepKey,
        ExecutionDomain domain,
        AgentRuntimeState.StepStatus status,
        String resultJson,
        String error,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public StepExecution {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(status, "status");
    }

    public StepExecution transition(AgentRuntimeState.StepStatus next, String result, String nextError, Instant now) {
        AgentRuntimeState.requireTransition(status, next);
        return new StepExecution(stepExecutionId, executionId, stepKey, domain, next, result, nextError,
                version + 1, createdAt, now);
    }
}
