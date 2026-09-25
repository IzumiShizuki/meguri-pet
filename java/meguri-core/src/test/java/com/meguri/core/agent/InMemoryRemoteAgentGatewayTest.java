package com.meguri.core.agent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRemoteAgentGatewayTest {
    @Test
    void usesStableIdsBoundedPollProgressionAndSchemaDerivedResult() {
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway(Duration.ZERO, 1);
        InvokeAgentProposal proposal = proposal();
        AgentTask task = task(proposal);

        String first = gateway.submit(task, proposal).block().remoteTaskId();
        String second = gateway.submit(task, proposal).block().remoteTaskId();

        assertThat(first).isEqualTo("local-agent-000001");
        assertThat(second).isEqualTo("local-agent-000002");
        assertThat(gateway.poll(first).block()).isEqualTo(RemoteAgentGateway.RemoteAgentStatus.RUNNING);
        assertThat(gateway.poll(first).block()).isEqualTo(RemoteAgentGateway.RemoteAgentStatus.SUCCEEDED);
        assertThat(gateway.result(first).block().payload())
                .containsEntry("answer", "local-result: local task")
                .containsEntry("count", 0);
        assertThat(gateway.metrics().totalSubmits()).isEqualTo(2);
    }

    @Test
    void explicitCompletionWaitingAndCancellationAreDeterministic() {
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway();
        String waiting = gateway.submit(task(proposal()), proposal()).block().remoteTaskId();
        gateway.waitExternally(waiting);
        assertThat(gateway.poll(waiting).block())
                .isEqualTo(RemoteAgentGateway.RemoteAgentStatus.WAITING_EXTERNAL);
        gateway.complete(waiting);
        assertThat(gateway.poll(waiting).block())
                .isEqualTo(RemoteAgentGateway.RemoteAgentStatus.SUCCEEDED);

        String cancelled = gateway.submit(task(proposal()), proposal()).block().remoteTaskId();
        gateway.cancel(cancelled).block();
        assertThat(gateway.poll(cancelled).block())
                .isEqualTo(RemoteAgentGateway.RemoteAgentStatus.CANCELLED);
        assertThatThrownBy(() -> gateway.result(cancelled).block())
                .hasMessageContaining("no successful result");
    }

    private static InvokeAgentProposal proposal() {
        return new InvokeAgentProposal(
                "local-agent",
                "local task",
                Map.of(),
                InvokeAgentProposal.InvocationMode.AWAIT,
                true,
                "local",
                Instant.now().plusSeconds(5),
                new AgentTaskContext.Budget(100, 1, BigDecimal.ONE, 2, 1),
                Set.of("local"),
                new InvokeAgentProposal.ResultSchema(
                        "local-result-v1",
                        Map.of(
                                "answer", InvokeAgentProposal.ValueType.STRING,
                                "count", InvokeAgentProposal.ValueType.NUMBER)),
                false,
                Duration.ofMillis(1),
                4);
    }

    private static AgentTask task(InvokeAgentProposal proposal) {
        Instant now = Instant.now();
        AgentTaskContext context = new AgentTaskContext(
                "tenant", "user", null, "trace", "span", "key", proposal.deadline(),
                new CancellationToken(), proposal.budget(), 1, proposal.allowedCapabilities(),
                proposal.taskBrief(), Map.of());
        return new AgentTask(
                "task", "execution", "step", null, "resource", null,
                "tenant", "user", "key", "hash", AgentRuntimeState.AgentStatus.RUNNING,
                context, null, null, 0, now, now);
    }
}
