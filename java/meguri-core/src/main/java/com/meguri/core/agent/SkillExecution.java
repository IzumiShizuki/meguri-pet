package com.meguri.core.agent;

import java.time.Instant;
import java.util.Objects;

public record SkillExecution(
        String executionId,
        String turnId,
        String skillId,
        String capabilitySnapshotVersion,
        AgentRuntimeState.SkillStatus status,
        Instant deadline,
        String error,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public SkillExecution {
        capabilitySnapshotVersion = capabilitySnapshotVersion == null
                || capabilitySnapshotVersion.isBlank()
                ? "capability:legacy"
                : capabilitySnapshotVersion.trim();
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public SkillExecution(
            String executionId,
            String turnId,
            String skillId,
            AgentRuntimeState.SkillStatus status,
            Instant deadline,
            String error,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this(executionId, turnId, skillId, "capability:legacy", status,
                deadline, error, version, createdAt, updatedAt);
    }

    public SkillExecution transition(AgentRuntimeState.SkillStatus next, String nextError, Instant now) {
        AgentRuntimeState.requireTransition(status, next);
        return new SkillExecution(executionId, turnId, skillId,
                capabilitySnapshotVersion, next, deadline, nextError,
                version + 1, createdAt, now);
    }
}
