package com.meguri.core.agent;

public record AgentInvocation(
        String executionId,
        String stepExecutionId,
        String taskId,
        String remoteTaskId,
        Status status,
        AgentResult result,
        String reason) {

    public enum Status {
        SUCCEEDED, ACCEPTED_DURABLE, SKIPPED, FAILED, CANCELLED, TIMED_OUT
    }
}
