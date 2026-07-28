package com.meguri.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeTest {
    private StepDispatcher dispatcher;

    @AfterEach
    void closeDispatcher() {
        if (dispatcher != null) dispatcher.close();
    }

    @Test
    void awaitPollsWithoutBlockingCallerAndValidatesUntrustedResult() {
        TestGateway gateway = new TestGateway();
        gateway.pollsBeforeSuccess.set(1);
        Fixture fixture = fixture(gateway, 4);
        InvokeAgentProposal proposal = proposal(
                "await", InvokeAgentProposal.InvocationMode.AWAIT, Instant.now().plusSeconds(2), true);

        long started = System.nanoTime();
        Mono<AgentInvocation> invocation = fixture.runtime.invokeAgent("turn", "parent", fixture.parent, proposal);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));

        StepVerifier.create(invocation)
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(AgentInvocation.Status.SUCCEEDED);
                    assertThat(result.result().trustLabel())
                            .isEqualTo(AgentResult.TrustLabel.UNTRUSTED_AGENT_RESULT);
                })
                .verifyComplete();
        assertThat(gateway.pollCalls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void durableAsyncPersistsWaitingAndCallbackResumes() {
        TestGateway gateway = new TestGateway();
        Fixture fixture = fixture(gateway, 4);
        InvokeAgentProposal proposal = proposal(
                "durable", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                Instant.now().plusSeconds(2), true);

        AgentInvocation accepted = fixture.runtime.invokeAgent("turn", "parent", fixture.parent, proposal).block();
        assertThat(accepted.status()).isEqualTo(AgentInvocation.Status.ACCEPTED_DURABLE);
        AgentTask waiting = fixture.store.findTask(accepted.taskId()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(AgentRuntimeState.AgentStatus.WAITING_EXTERNAL);
        assertThat(waiting.context().capabilitySnapshotVersion())
                .isEqualTo("capability:legacy");
        assertThat(fixture.store.findSkill(accepted.executionId()).orElseThrow()
                .capabilitySnapshotVersion()).isEqualTo("capability:legacy");
        assertThat(fixture.registry.resource("remote").orElseThrow().availableSubmitPermits()).isEqualTo(1);
        assertThat(fixture.registry.resource("remote").orElseThrow().availableInFlightPermits()).isEqualTo(3);

        AgentInvocation resumed = fixture.runtime.resume(accepted.remoteTaskId(), gateway.validResult()).block();
        assertThat(resumed.status()).isEqualTo(AgentInvocation.Status.SUCCEEDED);
        assertThat(fixture.store.findSkill(accepted.executionId()).orElseThrow().status())
                .isEqualTo(AgentRuntimeState.SkillStatus.SUCCEEDED);
        assertThat(fixture.registry.resource("remote").orElseThrow().availableInFlightPermits()).isEqualTo(4);
    }

    @Test
    void idempotentProposalSubmitsOnceAndChildLimitIsEnforced() {
        TestGateway gateway = new TestGateway();
        Fixture fixture = fixture(gateway, 4);
        InvokeAgentProposal proposal = proposal(
                "same", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                Instant.now().plusSeconds(2), true);

        fixture.runtime.invokeAgent("turn", "parent", fixture.parent, proposal).block();
        AgentInvocation replay = fixture.runtime.invokeAgent("turn", "parent", fixture.parent, proposal).block();
        assertThat(replay.reason()).isEqualTo("idempotent replay");
        assertThat(gateway.submitCalls.get()).isEqualTo(1);

        Fixture limited = fixture(new TestGateway(), 1);
        limited.runtime.invokeAgent("turn", "limited-parent", limited.parent,
                proposal("one", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), true)).block();
        StepVerifier.create(limited.runtime.invokeAgent("turn", "limited-parent", limited.parent,
                        proposal("two", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                                Instant.now().plusSeconds(2), true)))
                .expectErrorMatches(error -> error instanceof AgentPolicyException
                        && error.getMessage().contains("children"))
                .verify();
    }

    @Test
    void siblingReservationsCannotExpandTheAggregateParentBudget() {
        TestGateway gateway = new TestGateway();
        Fixture fixture = fixture(gateway, 4);
        AgentTaskContext constrained = new AgentTaskContext(
                "tenant", "user", null, "trace", "root-span", "aggregate-key",
                Instant.now().plusSeconds(10), "cap-snapshot-7",
                new CancellationToken(),
                new AgentTaskContext.Budget(
                        1_500, 3, new BigDecimal("1.50"), 4, 4),
                0, Set.of("read"), "parent", Map.of());

        fixture.runtime.invokeAgent(
                "turn", "aggregate-parent", constrained,
                proposal("first-budget", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), true)).block();

        StepVerifier.create(fixture.runtime.invokeAgent(
                        "turn", "aggregate-parent", constrained,
                        proposal("second-budget", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                                Instant.now().plusSeconds(2), true)))
                .expectErrorMatches(error -> error instanceof AgentPolicyException
                        && error.getMessage().contains("aggregate child budget"))
                .verify();
        assertThat(gateway.submitCalls.get()).isEqualTo(1);
    }

    @Test
    void parentCancellationPropagatesAndCancelsRemoteTask() {
        TestGateway gateway = new TestGateway();
        gateway.neverCompletes = true;
        Fixture fixture = fixture(gateway, 4);
        Mono<AgentInvocation> invocation = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent,
                proposal("cancel", InvokeAgentProposal.InvocationMode.AWAIT,
                        Instant.now().plusSeconds(5), true));

        StepVerifier.create(invocation)
                .then(() -> fixture.parent.cancellation().cancel())
                .expectError(AgentCancelledException.class)
                .verify(Duration.ofSeconds(1));
        assertThat(gateway.cancelCalls.get()).isEqualTo(1);
        assertThat(fixture.store.findResumable()).isEmpty();
    }

    @Test
    void parentCancellationContinuesToWatchDurableAsyncAfterAcceptance() {
        TestGateway gateway = new TestGateway();
        gateway.neverCompletes = true;
        Fixture fixture = fixture(gateway, 4);
        AgentInvocation accepted = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent,
                proposal("durable-cancel", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(5), true)).block();

        assertThat(accepted.status()).isEqualTo(AgentInvocation.Status.ACCEPTED_DURABLE);
        fixture.parent.cancellation().cancel();

        assertThat(gateway.cancelCalls.get()).isEqualTo(1);
        assertThat(fixture.store.findTask(accepted.taskId()).orElseThrow().status())
                .isEqualTo(AgentRuntimeState.AgentStatus.CANCELLED);
        assertThat(fixture.store.findResumable()).isEmpty();
        assertThat(fixture.registry.resource("remote").orElseThrow().availableInFlightPermits())
                .isEqualTo(4);
    }

    @Test
    void awaitSubscriptionDoesNotBlockCallerWhileRemoteAgentIsPending() throws InterruptedException {
        TestGateway gateway = new TestGateway() {
            @Override
            public Mono<RemoteAgentStatus> poll(String remoteTaskId) {
                return Mono.delay(Duration.ofMillis(250))
                        .map(ignored -> RemoteAgentStatus.SUCCEEDED);
            }
        };
        Fixture fixture = fixture(gateway, 4);
        AtomicReference<AgentInvocation> completed = new AtomicReference<>();
        CountDownLatch completion = new CountDownLatch(1);

        long started = System.nanoTime();
        var subscription = fixture.runtime.invokeAgent(
                        "turn", "parent", fixture.parent,
                        proposal("non-blocking-await", InvokeAgentProposal.InvocationMode.AWAIT,
                                Instant.now().plusSeconds(2), true))
                .subscribe(value -> {
                    completed.set(value);
                    completion.countDown();
                });
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(200);
        assertThat(completed.get()).isNull();
        assertThat(completion.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isNotNull();
        subscription.dispose();
    }

    @Test
    void timeoutTerminatesAndReleasesInFlightPermit() {
        TestGateway gateway = new TestGateway();
        gateway.neverCompletes = true;
        Fixture fixture = fixture(gateway, 4, 1);

        StepVerifier.create(fixture.runtime.invokeAgent(
                        "turn", "parent", fixture.parent,
                        proposal("timeout", InvokeAgentProposal.InvocationMode.AWAIT,
                                Instant.now().plusMillis(80), true)))
                .expectError(AgentDeadlineExceededException.class)
                .verify(Duration.ofSeconds(2));
        assertThat(fixture.registry.resource("remote").orElseThrow().availableInFlightPermits()).isEqualTo(1);
    }

    @Test
    void invalidSchemaSourceAndSensitiveOutputFailClosed() {
        TestGateway gateway = new TestGateway();
        Fixture fixture = fixture(gateway, 4);

        AgentInvocation schemaTask = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent,
                proposal("schema", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), true)).block();
        StepVerifier.create(fixture.runtime.resume(schemaTask.remoteTaskId(),
                        new AgentResult("wrong", "researcher", Map.of("answer", "x"), false, null)))
                .expectError(AgentPolicyException.class)
                .verify();

        AgentInvocation sensitiveTask = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent,
                proposal("sensitive", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), true)).block();
        StepVerifier.create(fixture.runtime.resume(sensitiveTask.remoteTaskId(),
                        new AgentResult("answer-v1", "researcher", Map.of("answer", "secret"), true, null)))
                .expectError(AgentPolicyException.class)
                .verify();

        AgentInvocation credentialTask = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent,
                proposal("credential", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), true)).block();
        StepVerifier.create(fixture.runtime.resume(credentialTask.remoteTaskId(),
                        new AgentResult(
                                "answer-v1", "researcher",
                                Map.of("answer", "Bearer abcdefghijklmnopqrstuvwxyz012345"),
                                false, null)))
                .expectErrorMatches(error -> error instanceof AgentPolicyException
                        && error.getMessage().contains("credential"))
                .verify();

        AgentInvocation extraFieldTask = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent,
                proposal("extra-field", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), true)).block();
        StepVerifier.create(fixture.runtime.resume(extraFieldTask.remoteTaskId(),
                        new AgentResult(
                                "answer-v1", "researcher",
                                Map.of("answer", "safe", "debug", "unexpected"),
                                false, null)))
                .expectErrorMatches(error -> error instanceof AgentPolicyException
                        && error.getMessage().contains("outside"))
                .verify();
    }

    @Test
    void terminalIdempotentReplayReturnsPersistedResultAndAccurateFailureStatus() {
        TestGateway gateway = new TestGateway();
        Fixture fixture = fixture(gateway, 4);
        InvokeAgentProposal succeededProposal = proposal(
                "terminal-success", InvokeAgentProposal.InvocationMode.AWAIT,
                Instant.now().plusSeconds(2), true);

        AgentInvocation succeeded = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent, succeededProposal).block();
        AgentInvocation replayed = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent, succeededProposal).block();

        assertThat(replayed.status()).isEqualTo(AgentInvocation.Status.SUCCEEDED);
        assertThat(replayed.taskId()).isEqualTo(succeeded.taskId());
        assertThat(replayed.result()).isEqualTo(succeeded.result());

        InvokeAgentProposal failedProposal = proposal(
                "terminal-failed", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                Instant.now().plusSeconds(2), true);
        AgentInvocation accepted = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent, failedProposal).block();
        StepVerifier.create(fixture.runtime.resume(
                        accepted.remoteTaskId(),
                        new AgentResult("wrong", "researcher", Map.of("answer", "bad"), false, null)))
                .expectError(AgentPolicyException.class)
                .verify();

        AgentInvocation failedReplay = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent, failedProposal).block();
        assertThat(failedReplay.status()).isEqualTo(AgentInvocation.Status.FAILED);
        assertThat(failedReplay.taskId()).isEqualTo(accepted.taskId());
    }

    @Test
    void parallelAwaitNeverExceedsCallerConcurrencyBound() {
        ConcurrencyGateway gateway = new ConcurrencyGateway();
        Fixture fixture = fixture(gateway, 8);
        var proposals = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> proposal(
                        "parallel-" + index, InvokeAgentProposal.InvocationMode.PARALLEL_AWAIT,
                        Instant.now().plusSeconds(3), true))
                .toList();

        var results = fixture.runtime.parallelAwait("turn", "parent", fixture.parent, proposals, 2).block();

        assertThat(results).hasSize(6);
        assertThat(gateway.maxActivePolls.get()).isLessThanOrEqualTo(2);
        assertThat(gateway.maxActivePolls.get()).isGreaterThan(0);
    }

    @Test
    void unhealthyAndCapacityLimitedOptionalAgentDegradeDeterministically() {
        TestGateway gateway = new TestGateway();
        Fixture fixture = fixture(gateway, 4, 1);
        fixture.registry.setHealth("remote", ExecutionResourceRegistry.Health.UNHEALTHY);
        StepVerifier.create(fixture.runtime.invokeAgent(
                        "turn", "parent", fixture.parent,
                        proposal("unhealthy", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                                Instant.now().plusSeconds(1), false)))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(AgentInvocation.Status.SKIPPED);
                    assertThat(result.reason()).contains("not healthy");
                })
                .verifyComplete();
        StepVerifier.create(fixture.runtime.invokeAgent(
                        "turn", "parent", fixture.parent,
                        proposal("unhealthy-required", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                                Instant.now().plusSeconds(1), true)))
                .expectError(AgentUnavailableException.class)
                .verify();

        fixture.registry.setHealth("remote", ExecutionResourceRegistry.Health.HEALTHY);
        fixture.runtime.invokeAgent("turn", "parent", fixture.parent,
                proposal("held", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), true)).block();
        AgentInvocation skipped = fixture.runtime.invokeAgent(
                "turn", "parent", fixture.parent,
                proposal("optional", InvokeAgentProposal.InvocationMode.DURABLE_ASYNC,
                        Instant.now().plusSeconds(2), false)).block();
        assertThat(skipped.status()).isEqualTo(AgentInvocation.Status.SKIPPED);
        assertThat(gateway.submitCalls.get()).isEqualTo(1);
    }

    private Fixture fixture(TestGateway gateway, int maxChildren) {
        return fixture(gateway, maxChildren, 4);
    }

    private Fixture fixture(TestGateway gateway, int maxChildren, int maxInFlight) {
        ExecutionResourceRegistry registry = new ExecutionResourceRegistry();
        registry.register(new ExecutionResourceRegistry.ResourceDescriptor(
                "remote", ExecutionDomain.REMOTE_AGENT, 1, maxInFlight, 4,
                maxInFlight, maxInFlight,
                "researcher", Set.of("read"), Set.of("answer-v1")));
        InMemoryAgentRuntimeStore store = new InMemoryAgentRuntimeStore();
        dispatcher = new StepDispatcher(1, 1, 1, 1);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentRuntime runtime = new AgentRuntime(
                registry, dispatcher, store, store, store, gateway,
                new AgentResultValidator(), mapper, Clock.systemUTC());
        AgentTaskContext parent = new AgentTaskContext(
                "tenant", "user", null, "trace", "root-span", "turn-key",
                Instant.now().plusSeconds(10), new CancellationToken(),
                new AgentTaskContext.Budget(10_000, 20, new BigDecimal("10"), 4, maxChildren),
                0, Set.of("read"), "parent", Map.of());
        return new Fixture(runtime, registry, store, parent);
    }

    private static InvokeAgentProposal proposal(
            String key, InvokeAgentProposal.InvocationMode mode, Instant deadline, boolean required) {
        return new InvokeAgentProposal(
                "researcher", "research the bounded topic", Map.of("topic", "t1"), mode, required,
                key, deadline, new AgentTaskContext.Budget(
                1_000, 2, BigDecimal.ONE, 4, 1),
                Set.of("read"),
                new InvokeAgentProposal.ResultSchema(
                        "answer-v1", Map.of("answer", InvokeAgentProposal.ValueType.STRING)),
                false, Duration.ofMillis(10), 100);
    }

    private record Fixture(
            AgentRuntime runtime,
            ExecutionResourceRegistry registry,
            InMemoryAgentRuntimeStore store,
            AgentTaskContext parent) {
    }

    private static class TestGateway implements RemoteAgentGateway {
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger submitCalls = new AtomicInteger();
        private final AtomicInteger pollCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger pollsBeforeSuccess = new AtomicInteger();
        private final AtomicReference<String> lastRemoteId = new AtomicReference<>();
        private volatile boolean neverCompletes;

        @Override
        public Mono<RemoteSubmission> submit(AgentTask task, InvokeAgentProposal proposal) {
            submitCalls.incrementAndGet();
            String id = "remote-" + sequence.incrementAndGet();
            lastRemoteId.set(id);
            return Mono.just(new RemoteSubmission(id));
        }

        @Override
        public Mono<RemoteAgentStatus> poll(String remoteTaskId) {
            int call = pollCalls.incrementAndGet();
            if (neverCompletes || call <= pollsBeforeSuccess.get()) {
                return Mono.just(RemoteAgentStatus.RUNNING);
            }
            return Mono.just(RemoteAgentStatus.SUCCEEDED);
        }

        @Override
        public Mono<Void> cancel(String remoteTaskId) {
            cancelCalls.incrementAndGet();
            return Mono.empty();
        }

        @Override
        public Mono<AgentResult> result(String remoteTaskId) {
            return Mono.just(validResult());
        }

        private AgentResult validResult() {
            return new AgentResult(
                    "answer-v1", "researcher", Map.of("answer", "bounded result"), false, null);
        }
    }

    private static final class ConcurrencyGateway extends TestGateway {
        private final AtomicInteger activePolls = new AtomicInteger();
        private final AtomicInteger maxActivePolls = new AtomicInteger();

        @Override
        public Mono<RemoteAgentStatus> poll(String remoteTaskId) {
            return Mono.defer(() -> {
                int active = activePolls.incrementAndGet();
                maxActivePolls.accumulateAndGet(active, Math::max);
                return Mono.delay(Duration.ofMillis(20))
                        .map(ignored -> RemoteAgentStatus.SUCCEEDED)
                        .doOnSuccess(ignored -> activePolls.decrementAndGet())
                        .doOnError(ignored -> activePolls.decrementAndGet())
                        .doOnCancel(activePolls::decrementAndGet);
            });
        }
    }
}
