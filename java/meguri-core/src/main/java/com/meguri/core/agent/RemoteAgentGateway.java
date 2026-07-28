package com.meguri.core.agent;

import reactor.core.publisher.Mono;

public interface RemoteAgentGateway {
    Mono<RemoteSubmission> submit(AgentTask task, InvokeAgentProposal proposal);

    Mono<RemoteAgentStatus> poll(String remoteTaskId);

    Mono<Void> cancel(String remoteTaskId);

    Mono<AgentResult> result(String remoteTaskId);

    record RemoteSubmission(String remoteTaskId) {
        public RemoteSubmission {
            if (remoteTaskId == null || remoteTaskId.isBlank()) {
                throw new IllegalArgumentException("remoteTaskId is required");
            }
        }
    }

    enum RemoteAgentStatus {
        QUEUED, RUNNING, WAITING_EXTERNAL, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
    }
}
