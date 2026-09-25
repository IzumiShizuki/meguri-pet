package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.agent.AgentRuntimeAssembly;
import com.meguri.core.agent.AgentRuntimeFactory;
import com.meguri.core.agent.InMemoryRemoteAgentGateway;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.context.ContextBundle;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.execution.TurnExecutionMode;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.AgentPlanningRequest;
import com.meguri.core.llm.ProviderRequest;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TurnCanonicalRemoteAgentIntegrationTest {
    private TurnOrchestrator orchestrator;
    private AgentRuntimeAssembly agents;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) orchestrator.reset();
        if (agents != null) agents.close();
    }

    @Test
    void fastDoesNotInvokeButApprovedAgentPlannerInjectsUntrustedAgentResult()
            throws Exception {
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        AtomicReference<AgentPlanningRequest> planning = new AtomicReference<>();
        AtomicReference<AgentPlanningRequest.Decision> decision =
                new AtomicReference<>(new AgentPlanningRequest.Decision(
                        AgentRuntimeFactory.DEFAULT_AGENT_ID,
                        "bounded remote research",
                        AgentPlanningRequest.ExecutionPreference.AWAIT));
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway(Duration.ZERO, 0);
        CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        orchestrator = runtime(captured, planning, decision, capabilities);
        orchestrator.configurePerformanceFeatures(true, false, false);
        agents = AgentRuntimeFactory.create(
                AgentRuntimeFactory.Config.defaults(), orchestrator::recordAgentLifecycle,
                Clock.systemUTC(), gateway);
        orchestrator.configureAgentRuntime(agents.runtime(), agents.config());

        TurnRecord fast = orchestrator.start(new TurnRequest(
                "agent-user", "website", "agent-fast", "answer locally",
                RetrievalMode.FAST));
        fast.getDone().join();

        assertThat(fast.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(gateway.metrics().totalSubmits()).isZero();
        assertThat(fast.getProviderRequest().context().blocks())
                .noneMatch(block -> block.sourceIds().contains("REMOTE_AGENT"));

        TurnRecord slow = orchestrator.start(new TurnRequest(
                "agent-user", "website", "agent-slow",
                "Research this with the bounded remote agent", RetrievalMode.SLOW)
                .withAuthorizedCapabilityScopes(Set.of("capability:read"))
                .withRequestedExecutionMode(TurnExecutionMode.AGENT));
        approveNext(capabilities);
        slow.getDone().join();

        assertThat(slow.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(gateway.metrics().totalSubmits()).isEqualTo(1);
        assertThat(planning.get()).isNotNull();
        assertThat(planning.get().context().context().blocks()).isNotEmpty();
        ContextBundle.Block agentBlock = slow.getProviderRequest().context().blocks().stream()
                .filter(block -> block.sourceIds().contains("REMOTE_AGENT"))
                .findFirst().orElseThrow();
        assertThat(agentBlock.blockType()).isEqualTo(ContextBundle.BlockType.TOOL_RESULT);
        assertThat(agentBlock.trust()).isEqualTo(ContextBundle.Trust.UNTRUSTED_EXTERNAL);
        assertThat(agentBlock.sourceIds())
                .anyMatch(source -> source.startsWith("trace:"))
                .anyMatch(source -> source.startsWith("execution:"))
                .anyMatch(source -> source.startsWith("task:"));
        assertThat(agentBlock.content())
                .contains("UNTRUSTED_AGENT_RESULT", "local-result:");
        assertThat(captured.get()).isSameAs(slow.getProviderRequest());
        assertThat(slow.getProviderRequest().promptBlocks())
                .filteredOn(block -> block.content().contains("local-result:"))
                .allSatisfy(block -> {
                    assertThat(block.role()).isEqualTo(PromptPolicyComposer.Role.USER_DATA);
                    assertThat(block.source()).isEqualTo(PromptPolicyComposer.Source.TOOL);
                    assertThat(block.trust()).isEqualTo(PromptPolicyComposer.Trust.UNTRUSTED);
                });
        assertThat(orchestrator.eventsFor("agent-slow"))
                .extracting(event -> event.getType())
                .contains("approval.required", "approval.resolved",
                        "agent.started", "agent.completed");
        assertThat(orchestrator.capabilityAuditEvents())
                .filteredOn(event -> event.capabilityId().equals("agent.invoke"))
                .anyMatch(event -> event.status().equals("SUCCESS"));
    }

    @Test
    void unexposedAgentCapabilityIsNotOfferedToPlanner() {
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        AtomicReference<AgentPlanningRequest> planning = new AtomicReference<>();
        AtomicReference<AgentPlanningRequest.Decision> decision =
                new AtomicReference<>(new AgentPlanningRequest.Decision(
                        AgentRuntimeFactory.DEFAULT_AGENT_ID,
                        "must not run",
                        AgentPlanningRequest.ExecutionPreference.AWAIT));
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway(Duration.ZERO, 0);
        CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        orchestrator = runtime(captured, planning, decision, capabilities);
        orchestrator.configurePerformanceFeatures(true, false, false);
        capabilities.disable("agent.invoke");
        agents = AgentRuntimeFactory.create(
                AgentRuntimeFactory.Config.defaults(), orchestrator::recordAgentLifecycle,
                Clock.systemUTC(), gateway);
        orchestrator.configureAgentRuntime(agents.runtime(), agents.config());

        TurnRecord turn = orchestrator.start(new TurnRequest(
                "agent-user", "website", "agent-denied", "try remote research",
                RetrievalMode.SLOW)
                .withAuthorizedCapabilityScopes(Set.of("capability:read"))
                .withRequestedExecutionMode(TurnExecutionMode.AGENT));
        turn.getDone().join();

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(gateway.metrics().totalSubmits()).isZero();
        assertThat(planning.get()).isNull();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().context().blocks())
                .noneMatch(block -> block.sourceIds().contains("REMOTE_AGENT"));
        assertThat(orchestrator.eventsFor("agent-denied"))
                .extracting(event -> event.getType())
                .noneMatch(type -> type.startsWith("agent."));
    }

    @Test
    void explicitProposalCanInvokeOutsideSlowAndRequiredDenialFailsTurn() {
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway(Duration.ZERO, 0);
        CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        orchestrator = runtime(
                captured, new AtomicReference<>(), new AtomicReference<>(),
                capabilities);
        agents = AgentRuntimeFactory.create(
                AgentRuntimeFactory.Config.defaults(), orchestrator::recordAgentLifecycle,
                Clock.systemUTC(), gateway);
        orchestrator.configureAgentRuntime(agents.runtime(), agents.config());

        TurnRequest explicit = new TurnRequest(
                "agent-user", "website", "agent-explicit", "local request",
                RetrievalMode.FAST).withAgentProposal(new TurnRequest.AgentProposal(
                        AgentRuntimeFactory.DEFAULT_AGENT_ID, "explicit bounded research",
                        false, "AWAIT", "explicit-fast"));
        TurnRecord accepted = orchestrator.start(explicit);
        accepted.getDone().join();

        assertThat(accepted.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(gateway.metrics().totalSubmits()).isEqualTo(1);
        assertThat(accepted.getProviderRequest().context().blocks())
                .anyMatch(block -> block.sourceIds().contains("REMOTE_AGENT"));

        capabilities.disable("agent.invoke");
        TurnRequest required = new TurnRequest(
                "agent-user", "website", "agent-required", "required request",
                RetrievalMode.FAST).withAgentProposal(new TurnRequest.AgentProposal(
                        AgentRuntimeFactory.DEFAULT_AGENT_ID, "required bounded research",
                        true, "AWAIT", "required-fast"));
        TurnRecord denied = orchestrator.start(required);
        denied.getDone().join();

        assertThat(denied.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(gateway.metrics().totalSubmits()).isEqualTo(1);
        assertThat(orchestrator.eventsFor("agent-required"))
                .extracting(event -> event.getType())
                .contains("agent.failed", "turn.failed");
    }

    @Test
    void durableAgentReferenceIsInjectedAsUntrustedWaitingContext() {
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway(Duration.ZERO, 0);
        CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        orchestrator = runtime(
                captured, new AtomicReference<>(), new AtomicReference<>(),
                capabilities);
        agents = AgentRuntimeFactory.create(
                AgentRuntimeFactory.Config.defaults(), orchestrator::recordAgentLifecycle,
                Clock.systemUTC(), gateway);
        orchestrator.configureAgentRuntime(agents.runtime(), agents.config());

        TurnRequest request = new TurnRequest(
                "agent-user", "website", "agent-durable", "continue asynchronously",
                RetrievalMode.FAST).withAgentProposal(new TurnRequest.AgentProposal(
                        AgentRuntimeFactory.DEFAULT_AGENT_ID, "durable bounded research",
                        false, "DURABLE_ASYNC", "durable-fast"));
        TurnRecord turn = orchestrator.start(request);
        turn.getDone().join();

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        ContextBundle.Block block = turn.getProviderRequest().context().blocks().stream()
                .filter(item -> item.sourceIds().contains("REMOTE_AGENT"))
                .findFirst().orElseThrow();
        assertThat(block.trust()).isEqualTo(ContextBundle.Trust.UNTRUSTED_EXTERNAL);
        assertThat(block.content()).contains("durable_reference", "remote_task_id");
        assertThat(orchestrator.eventsFor("agent-durable"))
                .extracting(event -> event.getType())
                .contains("agent.waiting")
                .doesNotContain("agent.failed");
    }

    @Test
    void plannerDeadlineFallsBackAndEmitsTheTimeoutReasonCode() {
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        orchestrator = timeoutPlannerRuntime(captured, capabilities);
        agents = AgentRuntimeFactory.create(
                AgentRuntimeFactory.Config.defaults(), orchestrator::recordAgentLifecycle,
                Clock.systemUTC(), new InMemoryRemoteAgentGateway(Duration.ZERO, 0));
        orchestrator.configureAgentRuntime(agents.runtime(), agents.config());
        orchestrator.configurePerformanceFeatures(true, false, false, 25L);

        TurnRecord turn = orchestrator.start(new TurnRequest(
                "agent-user", "website", "agent-planner-timeout", "research this",
                RetrievalMode.SLOW)
                .withAuthorizedCapabilityScopes(Set.of("capability:read"))
                .withRequestedExecutionMode(TurnExecutionMode.AGENT));
        turn.getDone().join();

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(captured.get()).isNotNull();
        assertThat(orchestrator.eventsFor("agent-planner-timeout"))
                .filteredOn(event -> event.getType().equals("agent.failed"))
                .singleElement()
                .satisfies(event -> assertThat(event.getData())
                        .containsEntry("error_code", "AGENT_PLANNER_TIMEOUT"));
    }

    @Test
    void slowRetrievalWithoutAnAgentDecisionDoesNotCallThePlanner() {
        AtomicReference<ProviderRequest> captured = new AtomicReference<>();
        AtomicReference<AgentPlanningRequest> planning = new AtomicReference<>();
        CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        orchestrator = runtime(captured, planning, new AtomicReference<>(), capabilities);
        agents = AgentRuntimeFactory.create(
                AgentRuntimeFactory.Config.defaults(), orchestrator::recordAgentLifecycle,
                Clock.systemUTC(), new InMemoryRemoteAgentGateway(Duration.ZERO, 0));
        orchestrator.configureAgentRuntime(agents.runtime(), agents.config());

        TurnRecord turn = orchestrator.start(new TurnRequest(
                "agent-user", "website", "ordinary-slow", "answer from current context",
                RetrievalMode.SLOW));
        turn.getDone().join();

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(captured.get()).isNotNull();
        assertThat(planning.get()).isNull();
        assertThat(orchestrator.eventsFor("ordinary-slow"))
                .extracting(event -> event.getType())
                .noneMatch(type -> type.startsWith("agent."));
    }

    private static TurnOrchestrator runtime(
            AtomicReference<ProviderRequest> captured,
            AtomicReference<AgentPlanningRequest> planning,
            AtomicReference<AgentPlanningRequest.Decision> decision,
            CapabilityRuntimeFacade capabilities) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RagProvider rag = (query, state, limit) -> List.of();
        LlmProvider provider = new LlmProvider() {
            @Override
            public Mono<LlmResponse> respond(ProviderRequest request) {
                captured.set(request);
                return Mono.just(new LlmResponse("provider reply"));
            }

            @Override
            public Mono<AgentPlanningRequest.Decision> planAgent(
                    AgentPlanningRequest request) {
                planning.set(request);
                return Mono.justOrEmpty(decision.get());
            }

            @Override
            public Mono<LlmResponse> respond(
                    TurnRequest request, RuntimeState state, List<String> canon,
                    List<String> memories, List<String> recentContext) {
                throw new AssertionError("canonical provider request must be used");
            }
        };
        return new TurnOrchestrator(
                provider, rag, new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ofMillis(1), mapper, new NoopMemoryGateway(),
                new NoopWebSearchGateway(), TrainingFeedbackService.disabled(mapper),
                WeatherConversationService.disabled(), new InMemoryTurnJournal(mapper),
                new NoopSessionContextPersistence(), capabilities);
    }

    private static TurnOrchestrator timeoutPlannerRuntime(
            AtomicReference<ProviderRequest> captured,
            CapabilityRuntimeFacade capabilities) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LlmProvider provider = new LlmProvider() {
            @Override
            public Mono<LlmResponse> respond(ProviderRequest request) {
                captured.set(request);
                return Mono.just(new LlmResponse("provider reply"));
            }

            @Override
            public Mono<AgentPlanningRequest.Decision> planAgent(
                    AgentPlanningRequest request) {
                return Mono.never();
            }

            @Override
            public Mono<LlmResponse> respond(
                    TurnRequest request, RuntimeState state, List<String> canon,
                    List<String> memories, List<String> recentContext) {
                throw new AssertionError("canonical provider request must be used");
            }
        };
        return new TurnOrchestrator(
                provider, (query, state, limit) -> List.of(), new RuntimeStateMachine(),
                new ExpressionResolver(), Duration.ofMillis(1), mapper, new NoopMemoryGateway(),
                new NoopWebSearchGateway(), TrainingFeedbackService.disabled(mapper),
                WeatherConversationService.disabled(), new InMemoryTurnJournal(mapper),
                new NoopSessionContextPersistence(), capabilities);
    }

    private static void approveNext(CapabilityRuntimeFacade capabilities)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var pending = capabilities.approvals().stream()
                    .filter(value -> value.decision()
                            == com.meguri.core.capability.ApprovalService.Decision.PENDING)
                    .findFirst();
            if (pending.isPresent()) {
                capabilities.resolveApproval(
                        pending.orElseThrow().approvalId(),
                        com.meguri.core.capability.ApprovalService.Decision.ACCEPT,
                        "test-user");
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("approval.required was not created");
    }
}
