package com.meguri.core.agent;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeAssemblyTest {
    @Test
    void defaultAssemblyRunsAwaitAndParallelAwaitWithoutManualDependencies() {
        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.createDefault()) {
            Instant deadline = Instant.now().plusSeconds(5);
            AgentTaskContext parent = assembly.rootContext(
                    "tenant", "user", "turn-default", deadline,
                    new AgentTaskContext.Budget(
                            20_000, 20, new BigDecimal("10.00"), 4, 8),
                    "root");

            AgentInvocation awaited = assembly.runtime().invokeAgent(
                    "turn-default", "parent", parent,
                    assembly.proposal("await task", InvokeAgentProposal.InvocationMode.AWAIT,
                            "await", deadline)).block();
            assertThat(awaited.status()).isEqualTo(AgentInvocation.Status.SUCCEEDED);
            assertThat(awaited.result().payload().get("answer")).isEqualTo("local-result: await task");

            List<InvokeAgentProposal> proposals = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> assembly.proposal(
                            "parallel " + index,
                            InvokeAgentProposal.InvocationMode.PARALLEL_AWAIT,
                            "parallel-" + index,
                            deadline))
                    .toList();
            List<AgentInvocation> parallel = assembly.runtime().parallelAwait(
                    "turn-default", "parent", parent, proposals, 2).block();
            assertThat(parallel).hasSize(4)
                    .allSatisfy(result -> assertThat(result.status())
                            .isEqualTo(AgentInvocation.Status.SUCCEEDED));
            assertThat(assembly.gateway().metrics().totalSubmits()).isEqualTo(5);
        }
    }

    @Test
    void durableAsyncKeepsInFlightPermitUntilCallbackAndCancelReleasesIt() {
        AgentRuntimeFactory.Config config = config(1, 2, Duration.ZERO);
        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                config, AgentLifecycleListener.noop(), Clock.systemUTC())) {
            Instant deadline = Instant.now().plusSeconds(5);
            AgentTaskContext parent = assembly.rootContext(
                    "tenant", "user", "turn-durable", deadline, "root");

            AgentInvocation waiting = assembly.runtime().invokeAgent(
                    "turn-durable", "parent", parent,
                    assembly.proposal("durable", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                            "durable", deadline)).block();
            var held = assembly.registry().resource(config.resourceId()).orElseThrow();
            assertThat(waiting.status()).isEqualTo(AgentInvocation.Status.ACCEPTED_DURABLE);
            assertThat(held.availableSubmitPermits()).isEqualTo(1);
            assertThat(held.availableInFlightPermits()).isEqualTo(1);
            assertThat(assembly.store().findResumable()).extracting(AgentTask::taskId)
                    .containsExactly(waiting.taskId());

            AgentInvocation resumed = assembly.completeAndResume(waiting.remoteTaskId()).block();
            assertThat(resumed.status()).isEqualTo(AgentInvocation.Status.SUCCEEDED);
            assertFullCapacity(assembly, config);

            AgentInvocation cancellable = assembly.runtime().invokeAgent(
                    "turn-durable", "parent", parent,
                    assembly.proposal("cancel", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                            "cancel", deadline)).block();
            assembly.cancel(cancellable.taskId()).block();
            assertThat(assembly.store().findTask(cancellable.taskId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.AgentStatus.CANCELLED);
            assertThat(assembly.gateway().task(cancellable.remoteTaskId()).orElseThrow().status())
                    .isEqualTo(RemoteAgentGateway.RemoteAgentStatus.CANCELLED);
            assertFullCapacity(assembly, config);
        }
    }

    @Test
    void defaultAssemblyEnforcesDualCapacityAndReleasesEveryPermit() {
        AgentRuntimeFactory.Config config = config(1, 2, Duration.ofMillis(25));
        CopyOnWriteArrayList<AgentLifecycleEvent> events = new CopyOnWriteArrayList<>();
        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                config, events::add, Clock.systemUTC())) {
            Instant deadline = Instant.now().plusSeconds(5);
            AgentTaskContext parent = assembly.rootContext(
                    "tenant", "user", "turn-capacity", deadline,
                    new AgentTaskContext.Budget(50_000, 50, new BigDecimal("20"), 4, 8),
                    "root");
            List<InvokeAgentProposal> proposals = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(index -> assembly.proposal(
                            "capacity " + index,
                            InvokeAgentProposal.InvocationMode.PARALLEL_AWAIT,
                            "capacity-" + index,
                            deadline))
                    .toList();

            List<AgentInvocation> results = assembly.runtime().parallelAwait(
                    "turn-capacity", "parent", parent, proposals, 6).block();

            assertThat(results).hasSize(6);
            assertThat(assembly.gateway().metrics().maxActiveSubmits()).isEqualTo(1);
            assertThat(maxHeld(events)).isLessThanOrEqualTo(2);
            assertFullCapacity(assembly, config);
        }
    }

    @Test
    void policyExpansionFailsBeforeGatewaySubmission() {
        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.createDefault()) {
            Instant parentDeadline = Instant.now().plusSeconds(3);
            AgentTaskContext parent = assembly.rootContext(
                    "tenant", "user", "turn-policy", parentDeadline,
                    new AgentTaskContext.Budget(100, 1, BigDecimal.ONE, 1, 2),
                    "root");
            InvokeAgentProposal expandedCapability = new InvokeAgentProposal(
                    AgentRuntimeFactory.DEFAULT_AGENT_ID,
                    "bad capability",
                    Map.of(),
                    InvokeAgentProposal.InvocationMode.AWAIT,
                    true,
                    "bad-capability",
                    parentDeadline,
                    new AgentTaskContext.Budget(50, 1, BigDecimal.ONE, 1, 1),
                    Set.of("admin"),
                    new InvokeAgentProposal.ResultSchema(
                            AgentRuntimeFactory.DEFAULT_RESULT_SCHEMA_ID,
                            Map.of("answer", InvokeAgentProposal.ValueType.STRING)),
                    false,
                    Duration.ofMillis(1),
                    2);
            InvokeAgentProposal expandedDeadline = new InvokeAgentProposal(
                    AgentRuntimeFactory.DEFAULT_AGENT_ID,
                    "bad deadline",
                    Map.of(),
                    InvokeAgentProposal.InvocationMode.AWAIT,
                    true,
                    "bad-deadline",
                    parentDeadline.plusSeconds(1),
                    new AgentTaskContext.Budget(50, 1, BigDecimal.ONE, 1, 1),
                    Set.of("local"),
                    new InvokeAgentProposal.ResultSchema(
                            AgentRuntimeFactory.DEFAULT_RESULT_SCHEMA_ID,
                            Map.of("answer", InvokeAgentProposal.ValueType.STRING)),
                    false,
                    Duration.ofMillis(1),
                    2);
            InvokeAgentProposal expandedBudget = new InvokeAgentProposal(
                    AgentRuntimeFactory.DEFAULT_AGENT_ID,
                    "bad budget",
                    Map.of(),
                    InvokeAgentProposal.InvocationMode.AWAIT,
                    true,
                    "bad-budget",
                    parentDeadline,
                    new AgentTaskContext.Budget(101, 1, BigDecimal.ONE, 1, 1),
                    Set.of("local"),
                    new InvokeAgentProposal.ResultSchema(
                            AgentRuntimeFactory.DEFAULT_RESULT_SCHEMA_ID,
                            Map.of("answer", InvokeAgentProposal.ValueType.STRING)),
                    false,
                    Duration.ofMillis(1),
                    2);

            StepVerifier.create(assembly.runtime().invokeAgent(
                            "turn-policy", "parent", parent, expandedCapability))
                    .expectError(AgentPolicyException.class)
                    .verify();
            StepVerifier.create(assembly.runtime().invokeAgent(
                            "turn-policy", "parent", parent, expandedDeadline))
                    .expectError(AgentPolicyException.class)
                    .verify();
            StepVerifier.create(assembly.runtime().invokeAgent(
                            "turn-policy", "parent", parent, expandedBudget))
                    .expectError(AgentPolicyException.class)
                    .verify();
            assertThat(assembly.gateway().metrics().totalSubmits()).isZero();
        }
    }

    @Test
    void lifecycleEventsAreOrderedAndListenerFailureCannotBreakRuntime() {
        CopyOnWriteArrayList<AgentLifecycleEvent> events = new CopyOnWriteArrayList<>();
        AgentLifecycleListener listener = event -> {
            events.add(event);
            if (event.type() == AgentLifecycleEvent.Type.STEP_CREATED) {
                throw new IllegalStateException("projection unavailable");
            }
        };
        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.createDefault(listener)) {
            Instant deadline = Instant.now().plusSeconds(3);
            AgentInvocation result = assembly.runtime().invokeAgent(
                    "turn-events",
                    "parent",
                    assembly.rootContext("tenant", "user", "turn-events", deadline, "root"),
                    assembly.proposal("events", InvokeAgentProposal.InvocationMode.AWAIT,
                            "events", deadline)).block();

            assertThat(result.status()).isEqualTo(AgentInvocation.Status.SUCCEEDED);
            assertThat(events).extracting(AgentLifecycleEvent::sequence).isSorted();
            assertThat(events).extracting(AgentLifecycleEvent::turnId)
                    .containsOnly("turn-events");
            assertThat(events).extracting(AgentLifecycleEvent::type).containsSubsequence(
                    AgentLifecycleEvent.Type.SKILL_CREATED,
                    AgentLifecycleEvent.Type.SKILL_STATUS_CHANGED,
                    AgentLifecycleEvent.Type.STEP_CREATED,
                    AgentLifecycleEvent.Type.TASK_CREATED,
                    AgentLifecycleEvent.Type.TASK_STATUS_CHANGED,
                    AgentLifecycleEvent.Type.CAPACITY_ACQUIRED,
                    AgentLifecycleEvent.Type.TASK_STATUS_CHANGED,
                    AgentLifecycleEvent.Type.SUBMIT_PERMIT_RELEASED,
                    AgentLifecycleEvent.Type.REMOTE_SUBMITTED,
                    AgentLifecycleEvent.Type.RESULT_VALIDATED,
                    AgentLifecycleEvent.Type.CAPACITY_RELEASED);
            assertThat(events.stream()
                    .filter(event -> event.type() == AgentLifecycleEvent.Type.TASK_STATUS_CHANGED)
                    .map(AgentLifecycleEvent::toStatus))
                    .containsSubsequence("QUEUED", "RUNNING", "WAITING_EXTERNAL", "SUCCEEDED");
        }
    }

    @Test
    void restartRestoresDurableInFlightCapacityWithoutHoldingSubmitPermit() {
        AgentRuntimeFactory.Config config = AgentRuntimeFactory.Config.defaults();
        InMemoryAgentRuntimeStore store = new InMemoryAgentRuntimeStore();
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway(
                config.submitDelay(), config.pollsBeforeSuccess());
        Instant deadline = Instant.now().plusSeconds(10);
        String taskId;
        try (AgentRuntimeAssembly first = AgentRuntimeFactory.create(
                config, AgentLifecycleListener.noop(), Clock.systemUTC(),
                gateway, store)) {
            AgentInvocation accepted = first.runtime().invokeAgent(
                    "turn-restart",
                    "parent",
                    first.rootContext(
                            "tenant", "user", "turn-restart", deadline, "root"),
                    first.proposal(
                            "durable",
                            InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                            "restart",
                            deadline))
                    .block();
            taskId = accepted.taskId();
        }

        try (AgentRuntimeAssembly restored = AgentRuntimeFactory.create(
                config, AgentLifecycleListener.noop(), Clock.systemUTC(),
                gateway, store)) {
            ExecutionResourceRegistry.ResourceSnapshot resource =
                    restored.registry().resource(config.resourceId()).orElseThrow();
            assertThat(resource.availableSubmitPermits())
                    .isEqualTo(config.submitMaxConcurrency());
            assertThat(resource.availableInFlightPermits())
                    .isEqualTo(config.maxInFlightTasks() - 1);

            restored.cancel(taskId).block();

            assertThat(restored.registry().resource(config.resourceId()).orElseThrow()
                    .availableInFlightPermits())
                    .isEqualTo(config.maxInFlightTasks());
        }
    }

    private static AgentRuntimeFactory.Config config(
            int submitMaxConcurrency, int maxInFlight, Duration submitDelay) {
        return new AgentRuntimeFactory.Config(
                "test-local-resource",
                AgentRuntimeFactory.DEFAULT_AGENT_ID,
                AgentRuntimeFactory.DEFAULT_RESULT_SCHEMA_ID,
                Set.of("local"),
                submitMaxConcurrency,
                maxInFlight,
                16,
                maxInFlight,
                maxInFlight,
                1,
                1,
                1,
                1,
                submitDelay,
                1,
                Duration.ofMillis(5),
                100,
                Duration.ofMillis(2));
    }

    private static int maxHeld(List<AgentLifecycleEvent> events) {
        AtomicInteger held = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        events.forEach(event -> {
            if (event.type() == AgentLifecycleEvent.Type.CAPACITY_ACQUIRED) {
                maximum.accumulateAndGet(held.incrementAndGet(), Math::max);
            } else if (event.type() == AgentLifecycleEvent.Type.CAPACITY_RELEASED) {
                held.decrementAndGet();
            }
        });
        assertThat(held.get()).isZero();
        return maximum.get();
    }

    private static void assertFullCapacity(
            AgentRuntimeAssembly assembly, AgentRuntimeFactory.Config config) {
        var snapshot = assembly.registry().resource(config.resourceId()).orElseThrow();
        assertThat(snapshot.availableSubmitPermits()).isEqualTo(config.submitMaxConcurrency());
        assertThat(snapshot.availableInFlightPermits()).isEqualTo(config.maxInFlightTasks());
    }
}
