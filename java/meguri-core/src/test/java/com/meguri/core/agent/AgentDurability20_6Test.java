package com.meguri.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDurability20_6Test {
    @Test
    void createdAndQueuedTasksReenterAdmissionWithoutPreRestoringCapacity() throws Exception {
        for (AgentRuntimeState.AgentStatus status : List.of(
                AgentRuntimeState.AgentStatus.CREATED,
                AgentRuntimeState.AgentStatus.QUEUED)) {
            RecoverySeed seed = seed(
                    status, null,
                    AgentRuntimeState.SkillStatus.RUNNING,
                    AgentRuntimeState.StepStatus.PENDING,
                    status.name().toLowerCase());

            try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                    seed.config(), AgentLifecycleListener.noop(), Clock.systemUTC(),
                    seed.gateway(), seed.store())) {
                var before = assembly.registry().resource(
                        seed.config().resourceId()).orElseThrow();
                assertThat(before.availableInFlightPermits())
                        .isEqualTo(seed.config().maxInFlightTasks());

                List<AgentInvocation> recovered =
                        assembly.runtime().resumeDurableTasks(1).block();

                assertThat(recovered).hasSize(1);
                AgentTask task = seed.store().findTask(seed.taskId()).orElseThrow();
                assertThat(task.status())
                        .isEqualTo(AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
                assertThat(task.idempotencyKey()).isEqualTo(seed.idempotencyKey());
                assertThat(seed.store().findChildren("parent")).hasSize(1);
                assertThat(seed.gateway().submitAttempts()).isEqualTo(1);
                assertThat(seed.gateway().lastSubmittedIdempotencyKey())
                        .isEqualTo(seed.idempotencyKey());
                assertThat(assembly.registry().resource(
                                seed.config().resourceId()).orElseThrow()
                        .availableInFlightPermits())
                        .isEqualTo(seed.config().maxInFlightTasks() - 1);
            }
        }
    }

    @Test
    void runningWithoutRemoteIdResubmitsWithStableIdempotencyWithoutDoubleCapacity() throws Exception {
        RecoverySeed seed = seed(
                AgentRuntimeState.AgentStatus.RUNNING, null,
                AgentRuntimeState.SkillStatus.RUNNING,
                AgentRuntimeState.StepStatus.RUNNING,
                "running-without-id");
        seed.gateway().accept(seed.idempotencyKey(), "remote-already-accepted");

        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                seed.config(), AgentLifecycleListener.noop(), Clock.systemUTC(),
                seed.gateway(), seed.store())) {
            var restored = assembly.registry().resource(
                    seed.config().resourceId()).orElseThrow();
            assertThat(restored.availableInFlightPermits())
                    .isEqualTo(seed.config().maxInFlightTasks() - 1);
            assertThat(restored.availableSubmitPermits())
                    .isEqualTo(seed.config().submitMaxConcurrency());

            assembly.runtime().resumeDurableTasks(1).block();

            AgentTask task = seed.store().findTask(seed.taskId()).orElseThrow();
            assertThat(task.status())
                    .isEqualTo(AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
            assertThat(task.remoteTaskId()).isEqualTo("remote-already-accepted");
            assertThat(task.idempotencyKey()).isEqualTo(seed.idempotencyKey());
            assertThat(seed.gateway().submitAttempts()).isEqualTo(1);
            assertThat(seed.gateway().acceptedTaskCount()).isEqualTo(1);
            var waiting = assembly.registry().resource(
                    seed.config().resourceId()).orElseThrow();
            assertThat(waiting.availableInFlightPermits())
                    .isEqualTo(seed.config().maxInFlightTasks() - 1);
            assertThat(waiting.availableSubmitPermits())
                    .isEqualTo(seed.config().submitMaxConcurrency());
        }
    }

    @Test
    void runningWithRemoteIdNormalizesAllStateBeforePolling() throws Exception {
        RecoverySeed seed = seed(
                AgentRuntimeState.AgentStatus.RUNNING, "remote-running",
                AgentRuntimeState.SkillStatus.RUNNING,
                AgentRuntimeState.StepStatus.RUNNING,
                "running-with-id");
        seed.gateway().onPoll(ignored -> {
            assertThat(seed.store().findTask(seed.taskId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
            assertThat(seed.store().findStep(seed.stepId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.StepStatus.WAITING);
            assertThat(seed.store().findSkill(seed.executionId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.SkillStatus.WAITING_REMOTE_AGENT);
        });

        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                seed.config(), AgentLifecycleListener.noop(), Clock.systemUTC(),
                seed.gateway(), seed.store())) {
            assertThat(assembly.registry().resource(
                            seed.config().resourceId()).orElseThrow()
                    .availableInFlightPermits())
                    .isEqualTo(seed.config().maxInFlightTasks() - 1);

            List<AgentInvocation> recovered =
                    assembly.runtime().resumeDurableTasks(1).block();

            assertThat(recovered).singleElement().satisfies(invocation ->
                    assertThat(invocation.status())
                            .isEqualTo(AgentInvocation.Status.SUCCEEDED));
            assertThat(seed.gateway().submitAttempts()).isZero();
            assertThat(seed.gateway().pollAttempts()).isEqualTo(1);
            assertThat(seed.store().findTask(seed.taskId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.AgentStatus.SUCCEEDED);
            assertThat(assembly.registry().resource(
                            seed.config().resourceId()).orElseThrow()
                    .availableInFlightPermits())
                    .isEqualTo(seed.config().maxInFlightTasks());
        }
    }

    @Test
    void waitingExternalContinuesPollingWithoutSubmittingOrDoubleReservingCapacity() throws Exception {
        RecoverySeed seed = seed(
                AgentRuntimeState.AgentStatus.WAITING_EXTERNAL, "remote-waiting",
                AgentRuntimeState.SkillStatus.WAITING_REMOTE_AGENT,
                AgentRuntimeState.StepStatus.WAITING,
                "waiting");

        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                seed.config(), AgentLifecycleListener.noop(), Clock.systemUTC(),
                seed.gateway(), seed.store())) {
            assertThat(assembly.registry().resource(
                            seed.config().resourceId()).orElseThrow()
                    .availableInFlightPermits())
                    .isEqualTo(seed.config().maxInFlightTasks() - 1);

            assembly.runtime().resumeDurableTasks(1).block();

            assertThat(seed.gateway().submitAttempts()).isZero();
            assertThat(seed.gateway().pollAttempts()).isEqualTo(1);
            assertThat(seed.store().findTask(seed.taskId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.AgentStatus.SUCCEEDED);
            assertThat(seed.store().findTask(seed.taskId()).orElseThrow()
                    .idempotencyKey()).isEqualTo(seed.idempotencyKey());
            assertThat(assembly.registry().resource(
                            seed.config().resourceId()).orElseThrow()
                    .availableInFlightPermits())
                    .isEqualTo(seed.config().maxInFlightTasks());
        }
    }

    @Test
    void recoversCrashBetweenRemoteAcceptanceAndRemoteIdPersistenceByIdempotentResubmit() throws Exception {
        AgentRuntimeFactory.Config config = AgentRuntimeFactory.Config.defaults();
        InMemoryAgentRuntimeStore store = new InMemoryAgentRuntimeStore();
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway();
        Instant now = Instant.now();
        Instant deadline = now.plusSeconds(5);
        InvokeAgentProposal proposal = new InvokeAgentProposal(
                config.agentId(), "recover", Map.of(),
                InvokeAgentProposal.InvocationMode.DURABLE_ASYNC, true, "recover",
                deadline, new AgentTaskContext.Budget(100, 1, java.math.BigDecimal.ONE, 2, 1),
                config.allowedCapabilities(), new InvokeAgentProposal.ResultSchema(
                config.resultSchemaId(), Map.of("answer", InvokeAgentProposal.ValueType.STRING)),
                false, config.pollInterval(), config.maxPollAttempts());
        String proposalJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(proposal);
        AgentTaskContext context = new AgentTaskContext(
                "tenant", "user", null, "trace", "span", "root/recover", deadline,
                "snapshot", new CancellationToken(), proposal.budget(), 1,
                proposal.allowedCapabilities(), proposal.taskBrief(),
                Map.of("_invoke_agent_proposal", proposalJson));
        store.create(new SkillExecution(
                "exec", "turn", "invoke_agent", "snapshot",
                AgentRuntimeState.SkillStatus.RUNNING, deadline, null, 0, now, now));
        store.create(new StepExecution(
                "step", "exec", "invoke_agent", ExecutionDomain.REMOTE_AGENT,
                AgentRuntimeState.StepStatus.PENDING, null, null, 0, now, now));
        store.createIdempotent(new AgentTask(
                "task", "exec", "step", null, config.resourceId(), null,
                "tenant", "user", "root/recover", "hash",
                AgentRuntimeState.AgentStatus.RUNNING, context,
                null, null, 0, now, now));

        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                config, AgentLifecycleListener.noop(), Clock.systemUTC(), gateway, store)) {
            AgentDurableRecoveryLifecycle recovery = new AgentDurableRecoveryLifecycle(
                    assembly.runtime(), Duration.ofMillis(10), 1);
            recovery.start();
            awaitStatus(store, "task", AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
            assertThat(gateway.metrics().totalSubmits()).isEqualTo(1);
            assertThat(store.findTask("task").orElseThrow()).satisfies(task -> {
                assertThat(task.status()).isEqualTo(AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
                assertThat(task.remoteTaskId()).isNotBlank();
            });

            AgentTaskContext periodicContext = new AgentTaskContext(
                    "tenant", "user", null, "trace", "periodic-span", "root/periodic", deadline,
                    "snapshot", new CancellationToken(), proposal.budget(), 1,
                    proposal.allowedCapabilities(), proposal.taskBrief(),
                    Map.of("_invoke_agent_proposal", proposalJson));
            store.create(new SkillExecution(
                    "periodic-exec", "turn", "invoke_agent", "snapshot",
                    AgentRuntimeState.SkillStatus.RUNNING, deadline, null, 0, now, now));
            store.create(new StepExecution(
                    "periodic-step", "periodic-exec", "invoke_agent", ExecutionDomain.REMOTE_AGENT,
                    AgentRuntimeState.StepStatus.PENDING, null, null, 0, now, now));
            store.createIdempotent(new AgentTask(
                    "periodic-task", "periodic-exec", "periodic-step", null,
                    config.resourceId(), null, "tenant", "user", "root/periodic", "hash-periodic",
                    AgentRuntimeState.AgentStatus.RUNNING, periodicContext,
                    null, null, 0, now, now));

            awaitStatus(store, "periodic-task", AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
            recovery.stop();
            assertThat(gateway.metrics().totalSubmits()).isEqualTo(2);
        }
    }

    @Test
    void recoveryConvergesExpiredDurableTaskToTimedOut() throws Exception {
        AgentRuntimeFactory.Config config = AgentRuntimeFactory.Config.defaults();
        InMemoryAgentRuntimeStore store = new InMemoryAgentRuntimeStore();
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway();
        Instant now = Instant.now();
        Instant deadline = now.minusSeconds(1);
        InvokeAgentProposal proposal = new InvokeAgentProposal(
                config.agentId(), "expired", Map.of(),
                InvokeAgentProposal.InvocationMode.DURABLE_ASYNC, true, "expired",
                deadline, new AgentTaskContext.Budget(100, 1, java.math.BigDecimal.ONE, 2, 1),
                config.allowedCapabilities(), new InvokeAgentProposal.ResultSchema(
                config.resultSchemaId(), Map.of("answer", InvokeAgentProposal.ValueType.STRING)),
                false, config.pollInterval(), config.maxPollAttempts());
        String proposalJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(proposal);
        AgentTaskContext context = new AgentTaskContext(
                "tenant", "user", null, "trace", "span", "root/expired", deadline,
                "snapshot", new CancellationToken(), proposal.budget(), 1,
                proposal.allowedCapabilities(), proposal.taskBrief(),
                Map.of("_invoke_agent_proposal", proposalJson));
        store.create(new SkillExecution(
                "expired-exec", "turn", "invoke_agent", "snapshot",
                AgentRuntimeState.SkillStatus.WAITING_REMOTE_AGENT,
                deadline, null, 0, now, now));
        store.create(new StepExecution(
                "expired-step", "expired-exec", "invoke_agent", ExecutionDomain.REMOTE_AGENT,
                AgentRuntimeState.StepStatus.WAITING, null, null, 0, now, now));
        store.createIdempotent(new AgentTask(
                "expired-task", "expired-exec", "expired-step", null,
                config.resourceId(), "remote-expired", "tenant", "user",
                "root/expired", "hash", AgentRuntimeState.AgentStatus.WAITING_EXTERNAL,
                context, null, null, 0, now, now));

        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.create(
                config, AgentLifecycleListener.noop(), Clock.systemUTC(), gateway, store)) {
            AgentDurableRecoveryLifecycle recovery = new AgentDurableRecoveryLifecycle(
                    assembly.runtime(), Duration.ofMillis(10), 4);
            recovery.start();
            awaitStatus(store, "expired-task", AgentRuntimeState.AgentStatus.TIMED_OUT);
            recovery.stop();

            assertThat(store.findSkill("expired-exec").orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.SkillStatus.TIMED_OUT);
            assertThat(store.findStep("expired-step").orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.StepStatus.TIMED_OUT);
            assertThat(gateway.metrics().totalSubmits()).isZero();
        }
    }

    @Test
    void cancellingParentTaskRecursivelyCancelsDurableChildren() {
        try (AgentRuntimeAssembly assembly = AgentRuntimeFactory.createDefault()) {
            Instant deadline = Instant.now().plusSeconds(5);
            AgentTaskContext root = assembly.rootContext(
                    "tenant", "user", "turn", deadline,
                    new AgentTaskContext.Budget(8_000, 8, new java.math.BigDecimal("4"), 4, 4),
                    "root");
            AgentInvocation parent = assembly.runtime().invokeAgent(
                    "turn", "root", root,
                    assembly.proposal("parent", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                            "parent", deadline)).block();
            AgentTask parentTask = assembly.store().findTask(parent.taskId()).orElseThrow();
            AgentInvocation child = assembly.runtime().invokeAgent(
                    "turn", parent.taskId(), parentTask.context(),
                    assembly.proposal("child", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                            "child", deadline)).block();

            assembly.runtime().cancelTask(parent.taskId()).block();

            assertThat(assembly.store().findTask(parent.taskId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.AgentStatus.CANCELLED);
            assertThat(assembly.store().findTask(child.taskId()).orElseThrow().status())
                    .isEqualTo(AgentRuntimeState.AgentStatus.CANCELLED);
            assertThat(assembly.gateway().task(child.remoteTaskId()).orElseThrow().status())
                    .isEqualTo(RemoteAgentGateway.RemoteAgentStatus.CANCELLED);
        }
    }

    private static void awaitStatus(
            InMemoryAgentRuntimeStore store,
            String taskId,
            AgentRuntimeState.AgentStatus expected) throws InterruptedException {
        Instant timeout = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(timeout)) {
            if (store.findTask(taskId).map(AgentTask::status).orElse(null) == expected) return;
            Thread.sleep(10L);
        }
        assertThat(store.findTask(taskId).orElseThrow().status()).isEqualTo(expected);
    }

    private static RecoverySeed seed(
            AgentRuntimeState.AgentStatus taskStatus,
            String remoteTaskId,
            AgentRuntimeState.SkillStatus skillStatus,
            AgentRuntimeState.StepStatus stepStatus,
            String suffix) throws Exception {
        AgentRuntimeFactory.Config config = AgentRuntimeFactory.Config.defaults();
        InMemoryAgentRuntimeStore store = new InMemoryAgentRuntimeStore();
        RecoveryGateway gateway = new RecoveryGateway(config);
        Instant now = Instant.now();
        Instant deadline = now.plusSeconds(5);
        String idempotencyKey = "root/" + suffix;
        String executionId = "exec-" + suffix;
        String stepId = "step-" + suffix;
        String taskId = "task-" + suffix;
        InvokeAgentProposal proposal = new InvokeAgentProposal(
                config.agentId(), "recover " + suffix, Map.of(),
                InvokeAgentProposal.InvocationMode.DURABLE_ASYNC, true, suffix,
                deadline, new AgentTaskContext.Budget(
                100, 1, java.math.BigDecimal.ONE, 2, 1),
                config.allowedCapabilities(), new InvokeAgentProposal.ResultSchema(
                config.resultSchemaId(), Map.of(
                "answer", InvokeAgentProposal.ValueType.STRING)),
                false, config.pollInterval(), config.maxPollAttempts());
        String proposalJson = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(proposal);
        AgentTaskContext context = new AgentTaskContext(
                "tenant", "user", "parent", "trace", "span-" + suffix,
                idempotencyKey, deadline, "snapshot", new CancellationToken(),
                proposal.budget(), 1, proposal.allowedCapabilities(),
                proposal.taskBrief(),
                Map.of("_invoke_agent_proposal", proposalJson));
        store.create(new SkillExecution(
                executionId, "turn", "invoke_agent", "snapshot",
                skillStatus, deadline, null, 0, now, now));
        store.create(new StepExecution(
                stepId, executionId, "invoke_agent", ExecutionDomain.REMOTE_AGENT,
                stepStatus, null, null, 0, now, now));
        store.createIdempotent(new AgentTask(
                taskId, executionId, stepId, "parent", config.resourceId(),
                remoteTaskId, "tenant", "user", idempotencyKey, "hash-" + suffix,
                taskStatus, context, null, null, 0, now, now));
        return new RecoverySeed(
                config, store, gateway, taskId, executionId, stepId,
                idempotencyKey);
    }

    private record RecoverySeed(
            AgentRuntimeFactory.Config config,
            InMemoryAgentRuntimeStore store,
            RecoveryGateway gateway,
            String taskId,
            String executionId,
            String stepId,
            String idempotencyKey) {
    }

    private static final class RecoveryGateway implements RemoteAgentGateway {
        private final AgentRuntimeFactory.Config config;
        private final ConcurrentHashMap<String, String> accepted =
                new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger submits = new AtomicInteger();
        private final AtomicInteger polls = new AtomicInteger();
        private volatile String lastSubmittedIdempotencyKey;
        private volatile Consumer<String> pollObserver = ignored -> { };

        private RecoveryGateway(AgentRuntimeFactory.Config config) {
            this.config = config;
        }

        @Override
        public reactor.core.publisher.Mono<RemoteSubmission> submit(
                AgentTask task, InvokeAgentProposal proposal) {
            submits.incrementAndGet();
            lastSubmittedIdempotencyKey = task.idempotencyKey();
            String remoteTaskId = accepted.computeIfAbsent(
                    task.idempotencyKey(),
                    ignored -> "remote-" + sequence.incrementAndGet());
            return reactor.core.publisher.Mono.just(
                    new RemoteSubmission(remoteTaskId));
        }

        @Override
        public reactor.core.publisher.Mono<RemoteAgentStatus> poll(
                String remoteTaskId) {
            polls.incrementAndGet();
            pollObserver.accept(remoteTaskId);
            return reactor.core.publisher.Mono.just(
                    RemoteAgentStatus.SUCCEEDED);
        }

        @Override
        public reactor.core.publisher.Mono<Void> cancel(String remoteTaskId) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<AgentResult> result(
                String remoteTaskId) {
            return reactor.core.publisher.Mono.just(new AgentResult(
                    config.resultSchemaId(), config.agentId(),
                    Map.of("answer", "recovered"), false, null));
        }

        private void accept(String idempotencyKey, String remoteTaskId) {
            accepted.put(idempotencyKey, remoteTaskId);
        }

        private void onPoll(Consumer<String> observer) {
            pollObserver = observer;
        }

        private int submitAttempts() {
            return submits.get();
        }

        private int pollAttempts() {
            return polls.get();
        }

        private int acceptedTaskCount() {
            return accepted.size();
        }

        private String lastSubmittedIdempotencyKey() {
            return lastSubmittedIdempotencyKey;
        }
    }
}
