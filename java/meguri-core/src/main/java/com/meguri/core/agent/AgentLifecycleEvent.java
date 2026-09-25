package com.meguri.core.agent;

import java.time.Instant;
import java.util.Objects;

public record AgentLifecycleEvent(
        long sequence,
        Instant occurredAt,
        Type type,
        String turnId,
        String executionId,
        String stepExecutionId,
        String taskId,
        String remoteTaskId,
        String fromStatus,
        String toStatus,
        String detail) {

    public AgentLifecycleEvent {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(type, "type");
    }

    public enum Type {
        SKILL_CREATED,
        SKILL_STATUS_CHANGED,
        STEP_CREATED,
        STEP_STATUS_CHANGED,
        TASK_CREATED,
        TASK_STATUS_CHANGED,
        CAPACITY_ACQUIRED,
        SUBMIT_PERMIT_RELEASED,
        REMOTE_SUBMITTED,
        DURABLE_WAITING,
        RESULT_VALIDATED,
        CAPACITY_RELEASED,
        IDEMPOTENT_REPLAY
    }
}
