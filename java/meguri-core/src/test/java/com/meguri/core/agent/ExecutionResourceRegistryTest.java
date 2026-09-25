package com.meguri.core.agent;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionResourceRegistryTest {
    @Test
    void submitAndInFlightPermitsAreIndependentAndTerminalReleaseRestoresCapacity() {
        ExecutionResourceRegistry registry = registry(1, 2);
        var request = request(false);

        var first = registry.acquire("remote", request).block();
        StepVerifier.create(registry.acquire("remote", request))
                .expectError(AgentSkippedException.class)
                .verify();

        first.releaseSubmit();
        var second = registry.acquire("remote", request).block();
        second.releaseSubmit();
        StepVerifier.create(registry.acquire("remote", request))
                .expectError(AgentSkippedException.class)
                .verify();

        first.close();
        second.close();
        var snapshot = registry.resource("remote").orElseThrow();
        assertThat(snapshot.availableSubmitPermits()).isEqualTo(1);
        assertThat(snapshot.availableInFlightPermits()).isEqualTo(2);
    }

    @Test
    void unhealthyAgentIsNeverSelectedAndRequiredQueueIsDeadlineBounded() {
        ExecutionResourceRegistry registry = registry(1, 1);
        registry.setHealth("remote", ExecutionResourceRegistry.Health.UNHEALTHY);

        StepVerifier.create(registry.acquire("remote", request(true)))
                .expectError(AgentUnavailableException.class)
                .verify();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        registry.selectHealthyRemoteAgent("researcher", Set.of("read"), "answer-v1"))
                .isInstanceOf(AgentUnavailableException.class);

        registry.setHealth("remote", ExecutionResourceRegistry.Health.HEALTHY);
        var held = registry.acquire("remote", request(false)).block();
        held.releaseSubmit();
        StepVerifier.create(registry.acquire("remote", new ExecutionResourceRegistry.AdmissionRequest(
                        "tenant", "user-2", Instant.now().plusMillis(30), true, new CancellationToken())))
                .expectError(AgentDeadlineExceededException.class)
                .verify(Duration.ofSeconds(1));
        held.close();
    }

    private static ExecutionResourceRegistry registry(int submit, int inFlight) {
        ExecutionResourceRegistry registry = new ExecutionResourceRegistry();
        registry.register(new ExecutionResourceRegistry.ResourceDescriptor(
                "remote", ExecutionDomain.REMOTE_AGENT, submit, inFlight, 2, inFlight, inFlight,
                "researcher", Set.of("read"), Set.of("answer-v1")));
        return registry;
    }

    private static ExecutionResourceRegistry.AdmissionRequest request(boolean required) {
        return new ExecutionResourceRegistry.AdmissionRequest(
                "tenant", "user", Instant.now().plusSeconds(1), required, new CancellationToken());
    }
}
