package com.meguri.core.agent;

import reactor.core.publisher.Mono;

/**
 * Fail-closed gateway used when no real remote-agent transport is configured.
 */
public final class UnavailableRemoteAgentGateway implements RemoteAgentGateway {
    private final String reason;

    public UnavailableRemoteAgentGateway(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        this.reason = reason.trim();
    }

    @Override
    public Mono<RemoteSubmission> submit(
            AgentTask task, InvokeAgentProposal proposal) {
        return unavailable();
    }

    @Override
    public Mono<RemoteAgentStatus> poll(String remoteTaskId) {
        return unavailable();
    }

    @Override
    public Mono<Void> cancel(String remoteTaskId) {
        return unavailable();
    }

    @Override
    public Mono<AgentResult> result(String remoteTaskId) {
        return unavailable();
    }

    private <T> Mono<T> unavailable() {
        return Mono.error(new AgentUnavailableException(reason));
    }
}
