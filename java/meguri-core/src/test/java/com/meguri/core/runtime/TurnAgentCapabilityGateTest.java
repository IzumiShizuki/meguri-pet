package com.meguri.core.runtime;

import com.meguri.core.capability.ApprovalService;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.llm.LlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnAgentCapabilityGateTest {
    private TurnOrchestrator orchestrator;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) orchestrator.reset();
    }

    @Test
    void explicitAgentInvocationUsesOwningTurnSnapshotApprovalAndAudit() {
        LlmProvider waitingProvider = (request, state, canon, memories, recent) ->
                Mono.never();
        orchestrator = new TurnOrchestrator(
                waitingProvider, (query, state, limit) -> List.of());
        TurnRequest request = new TurnRequest(
                "user", "website", "session", "delegate this", RetrievalMode.SLOW);
        TurnRecord turn = orchestrator.start(request);
        AtomicInteger calls = new AtomicInteger();

        String result = orchestrator.executeAgentCapability(
                        turn.getTurnId(),
                        request.tenantId(),
                        request.getUserId(),
                        request.getClientId(),
                        request.getSessionId(),
                        Map.of(
                                "agent_id", "research",
                                "task_brief", "bounded task",
                                "idempotency_key", "same-agent-call"),
                        () -> {
                            calls.incrementAndGet();
                            return Mono.just("accepted");
                        })
                .block(Duration.ofSeconds(2));
        String replay = orchestrator.executeAgentCapability(
                        turn.getTurnId(),
                        request.tenantId(),
                        request.getUserId(),
                        request.getClientId(),
                        request.getSessionId(),
                        Map.of(
                                "agent_id", "research",
                                "task_brief", "bounded task",
                                "idempotency_key", "same-agent-call"),
                        () -> {
                            calls.incrementAndGet();
                            return Mono.just("duplicate");
                        })
                .block(Duration.ofSeconds(2));

        assertThat(result).isEqualTo("accepted");
        assertThat(replay).isEqualTo("accepted");
        assertThat(calls).hasValue(1);
        assertThat(orchestrator.capabilityAuditEvents())
                .filteredOn(event -> event.capabilityId().equals("agent.invoke"))
                .anySatisfy(event -> {
                    assertThat(event.status()).isEqualTo("SUCCESS");
                    assertThat(event.approvalDecision())
                            .isEqualTo(ApprovalService.Decision.ACCEPT);
                });
        assertThat(orchestrator.eventsFor(request.getSessionId()))
                .extracting(event -> event.getType())
                .contains("approval.required", "approval.resolved");

        assertThatThrownBy(() -> orchestrator.executeAgentCapability(
                        turn.getTurnId(),
                        request.tenantId(),
                        "another-user",
                        request.getClientId(),
                        request.getSessionId(),
                        Map.of("agent_id", "research"),
                        () -> Mono.just("must-not-run"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void agentScopeInheritsFrozenTurnDeadlineTraceSnapshotAndCancellation() {
        LlmProvider waitingProvider = (request, state, canon, memories, recent) ->
                Mono.never();
        orchestrator = new TurnOrchestrator(
                waitingProvider, (query, state, limit) -> List.of());
        TurnRequest request = new TurnRequest(
                "user", "website", "scope-session", "delegate", RetrievalMode.SLOW);
        TurnRecord turn = orchestrator.start(request);
        AtomicReference<TurnOrchestrator.AgentExecutionScope> captured =
                new AtomicReference<>();

        String result = orchestrator.executeAgentCapability(
                        turn.getTurnId(),
                        request.tenantId(),
                        request.getUserId(),
                        request.getClientId(),
                        request.getSessionId(),
                        Map.of(
                                "agent_id", "research",
                                "idempotency_key", "agent-scope"),
                        scope -> {
                            captured.set(scope);
                            return Mono.just("accepted");
                        })
                .block(Duration.ofSeconds(2));

        assertThat(result).isEqualTo("accepted");
        TurnOrchestrator.AgentExecutionScope scope = captured.get();
        assertThat(scope).isNotNull();
        assertThat(scope.deadlineAt()).isEqualTo(turn.getDeadlineAt());
        assertThat(scope.traceId()).isEqualTo(turn.getTraceId());
        assertThat(scope.capabilitySnapshotVersion())
                .isEqualTo(turn.getRuntimeCapabilities().snapshotId());
        assertThat(scope.cancellation().isCancelled()).isFalse();

        orchestrator.cancel(turn.getTurnId());

        assertThat(scope.cancellation().isCancelled()).isTrue();
        assertThat(orchestrator.capabilityAuditEvents())
                .filteredOn(event -> event.capabilityId().equals("agent.invoke"))
                .allSatisfy(event -> {
                    assertThat(event.operationId()).isNotBlank();
                    assertThat(event.idempotencyKey()).isEqualTo("agent-scope");
                });
    }
}
