package com.meguri.core.agent;

import java.time.Instant;
import java.util.Objects;

public record AgentTask(
        String taskId,
        String executionId,
        String stepExecutionId,
        String parentTaskId,
        String resourceId,
        String remoteTaskId,
        String tenantId,
        String userId,
        String idempotencyKey,
        String payloadHash,
        AgentRuntimeState.AgentStatus status,
        AgentTaskContext context,
        String resultJson,
        String error,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public AgentTask {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(context, "context");
    }

    public AgentTask withRemoteTask(String value, Instant now) {
        if (remoteTaskId != null && !remoteTaskId.equals(value)) {
            throw new IllegalStateException("remote task id is immutable");
        }
        return new AgentTask(taskId, executionId, stepExecutionId, parentTaskId, resourceId, value,
                tenantId, userId, idempotencyKey, payloadHash, status, context, resultJson, error,
                version + 1, createdAt, now);
    }

    public AgentTask transition(AgentRuntimeState.AgentStatus next, String result, String nextError, Instant now) {
        AgentRuntimeState.requireTransition(status, next);
        return new AgentTask(taskId, executionId, stepExecutionId, parentTaskId, resourceId, remoteTaskId,
                tenantId, userId, idempotencyKey, payloadHash, next, context, result, nextError,
                version + 1, createdAt, now);
    }
}
