package com.meguri.core.react;

import com.meguri.core.agent.CancellationToken;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.execution.ExecutionBudget;
import com.meguri.core.execution.TurnExecutionMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LimitedReActRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void thinkModeCannotStartPlannerOrToolLoop() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        InMemoryReactTraceRepository traces = new InMemoryReactTraceRepository();
        LimitedReActRuntime runtime = runtime(
                context -> {
                    plannerCalls.incrementAndGet();
                    return Mono.just(finalizeDecision());
                },
                (action, context) -> {
                    toolCalls.incrementAndGet();
                    return Mono.just(success("unused"));
                }, traces);

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.THINK, budget(3, 3, 2, 100, 100),
                List.of(read("repository.search")), new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.MODE_NOT_AGENT);
        assertThat(result.rounds()).isZero();
        assertThat(plannerCalls).hasValue(0);
        assertThat(toolCalls).hasValue(0);
        assertThat(traces.result("turn-react"))
                .isEqualTo(ReactExecutionSummary.from(result));
    }

    @Test
    void testSubagentSkillCompletesOneBoundedReadBeforeFinalizing() {
        TestSubagentSkill skill = new TestSubagentSkill();
        InMemoryReactTraceRepository traces = new InMemoryReactTraceRepository();
        LimitedReActRuntime runtime = runtime(skill, skill, traces);

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(3, 3, 2, 100, 100),
                List.of(read(TestSubagentSkill.CAPABILITY_ID)), new CancellationToken()))
                .block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.MODEL_FINALIZED);
        assertThat(result.finalAnswer()).contains("ordinary-conversation");
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(skill.plannerCalls()).isEqualTo(2);
        assertThat(skill.actionCalls()).isEqualTo(1);
        assertThat(traces.traces("turn-react")).hasSize(2);
    }

    @Test
    void promptSkillCanBeSelectedAfterAnEarlierObservation() {
        ReactAction read = new ReactAction("repository.search", Map.of("query", "weather"));
        ReactAction skill = new ReactAction("skill.weather.advice", Map.of());
        AtomicInteger plannerIndex = new AtomicInteger();
        List<ReactPlannerDecision> decisions = List.of(
                continueWith(read), continueWith(skill), finalizeDecision());
        AtomicInteger executions = new AtomicInteger();
        InMemoryReactTraceRepository traces = new InMemoryReactTraceRepository();
        LimitedReActRuntime runtime = runtime(
                context -> {
                    assertThat(context.observations()).hasSize(plannerIndex.get());
                    assertThat(context.exposedCapabilities())
                            .extracting(ReactCapability::id)
                            .containsExactly("repository.search", "skill.weather.advice");
                    return Mono.just(decisions.get(plannerIndex.getAndIncrement()));
                },
                (validated, context) -> {
                    executions.incrementAndGet();
                    return Mono.just(success(validated.descriptor().kind()
                            == CapabilityDescriptor.Kind.PROMPT_SKILL
                            ? "bounded skill content" : "weather evidence"));
                }, traces);

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(6, 4, 2, 100, 100),
                List.of(read("repository.search"), promptSkill("skill.weather.advice")),
                new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.MODEL_FINALIZED);
        assertThat(result.rounds()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.observations()).extracting(NormalizedReactObservation::summary)
                .containsExactly("weather evidence", "bounded skill content");
        assertThat(executions).hasValue(2);
        assertThat(traces.traces("turn-react"))
                .filteredOn(trace -> "skill.weather.advice".equals(trace.capabilityId()))
                .extracting(ReactRoundTrace::observationSummary)
                .containsExactly("[bounded Skill observation redacted]");
        assertThat(traces.traces("turn-react").toString())
                .doesNotContain("arguments", "bounded skill content");
    }

    @Test
    void externalSkillBodyCanGuideTheNextRoundWithoutEnteringTrace() {
        String skillBody = "Use repository.search query=meguri-context. TOP_SECRET";
        ReactAction view = new ReactAction("meguri.skill.view", Map.of(
                "skill_id", "modelscope:context-audit", "path", "SKILL.md"));
        ReactAction followUp = new ReactAction(
                "repository.search", Map.of("query", "meguri-context"));
        AtomicInteger plannerIndex = new AtomicInteger();
        AtomicInteger actionIndex = new AtomicInteger();
        InMemoryReactTraceRepository traces = new InMemoryReactTraceRepository();
        LimitedReActRuntime runtime = runtime(
                context -> {
                    int round = plannerIndex.getAndIncrement();
                    assertThat(context.skillCandidates())
                            .extracting(ReactSkillCandidate::skillId)
                            .containsExactly("modelscope:context-audit");
                    if (round == 0) return Mono.just(continueWith(view));
                    assertThat(context.observations().getFirst().summary())
                            .isEqualTo(skillBody);
                    assertThat(context.observations().getFirst().trustLabel())
                            .isEqualTo(ObservationTrustLabel.UNTRUSTED_EXTERNAL_SKILL);
                    if (round == 1) return Mono.just(continueWith(followUp));
                    return Mono.just(finalizeDecision());
                },
                (validated, context) -> {
                    int call = actionIndex.getAndIncrement();
                    if (call == 0) {
                        return Mono.just(new RawReactObservation(
                                RawReactObservation.Status.SUCCESS,
                                Map.of("content_digest", "digest-only"),
                                skillBody, null,
                                ObservationTrustLabel.UNTRUSTED_EXTERNAL_SKILL,
                                false, false, 8, 0));
                    }
                    return Mono.just(success("repository evidence"));
                }, traces);

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(8, 8, 2, 200, 100),
                List.of(read("meguri.skill.view"), read("repository.search")),
                List.of(new ReactSkillCandidate(
                        "modelscope:context-audit", "Context Audit",
                        "Audits context behavior", List.of("context"))),
                new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.MODEL_FINALIZED);
        assertThat(result.rounds()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(actionIndex).hasValue(2);
        assertThat(traces.traces("turn-react"))
                .filteredOn(trace -> "meguri.skill.view".equals(trace.capabilityId()))
                .extracting(ReactRoundTrace::observationSummary)
                .containsExactly("[bounded Skill observation redacted]");
        assertThat(traces.traces("turn-react").toString())
                .doesNotContain("TOP_SECRET", "query=meguri-context");
    }

    @Test
    void limitedReactCeilingsNeverExpandAParentBudget() {
        ReactRunRequest broad = request(
                TurnExecutionMode.AGENT, budget(10, 10, 2, 100, 100),
                List.of(read("repository.search")), new CancellationToken());
        ReactRunRequest narrow = request(
                TurnExecutionMode.AGENT, budget(2, 1, 1, 100, 100),
                List.of(read("repository.search")), new CancellationToken());

        assertThat(broad.budget().maxModelCalls()).isEqualTo(6);
        assertThat(broad.budget().maxRounds()).isEqualTo(6);
        assertThat(broad.budget().maxToolCalls()).isEqualTo(4);
        assertThat(narrow.budget().maxModelCalls()).isEqualTo(2);
        assertThat(narrow.budget().maxRounds()).isEqualTo(2);
        assertThat(narrow.budget().maxToolCalls()).isEqualTo(1);
    }

    @Test
    void unsafeOrUnexposedPromptSkillIsRejectedBeforeExecution() {
        AtomicInteger executions = new AtomicInteger();
        LimitedReActRuntime runtime = runtime(
                context -> Mono.just(continueWith(new ReactAction("skill.hidden", Map.of()))),
                (validated, context) -> {
                    executions.incrementAndGet();
                    return Mono.just(success("must not run"));
                }, new InMemoryReactTraceRepository());

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(6, 4, 2, 100, 100),
                List.of(promptSkill("skill.visible")), new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.ACTION_REJECTED);
        assertThat(executions).hasValue(0);
    }

    @Test
    void repeatedSuccessfulActionReusesNormalizedObservationWithoutExecutingAgain() {
        ReactAction action = new ReactAction(
                "repository.search", Map.of("query", "UserCenterClient"));
        AtomicInteger plannerIndex = new AtomicInteger();
        List<ReactPlannerDecision> decisions = List.of(
                continueWith(action), continueWith(action), finalizeDecision());
        AtomicInteger toolCalls = new AtomicInteger();
        InMemoryReactTraceRepository traces = new InMemoryReactTraceRepository();
        LimitedReActRuntime runtime = runtime(
                context -> Mono.just(decisions.get(plannerIndex.getAndIncrement())),
                (validated, context) -> {
                    toolCalls.incrementAndGet();
                    return Mono.just(new RawReactObservation(
                            RawReactObservation.Status.SUCCESS,
                            Map.of("secret", "must-not-be-persisted", "value", "found"),
                            "  dependency   found  ",
                            null,
                            ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                            false,
                            false,
                            7,
                            2));
                }, traces);

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(3, 3, 2, 100, 100),
                List.of(read("repository.search")), new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.MODEL_FINALIZED);
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.rounds()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.tokensUsed()).isEqualTo(7);
        assertThat(result.costUnitsUsed()).isEqualTo(2);
        assertThat(result.observations()).hasSize(2);
        assertThat(result.observations().get(1).reused()).isTrue();
        assertThat(result.observations())
                .allMatch(value -> value.trustLabel()
                        == ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT);
        assertThat(toolCalls).hasValue(1);
        assertThat(traces.traces("turn-react")).hasSize(3);
        assertThat(traces.traces("turn-react").toString())
                .doesNotContain("must-not-be-persisted");
    }

    @Test
    void repeatedActionLimitTerminatesBeforeSecondExecution() {
        ReactAction action = new ReactAction("repository.search", Map.of("query", "same"));
        AtomicInteger plannerIndex = new AtomicInteger();
        List<ReactPlannerDecision> decisions = List.of(
                continueWith(action), continueWith(action));
        AtomicInteger toolCalls = new AtomicInteger();
        InMemoryReactTraceRepository traces = new InMemoryReactTraceRepository();
        LimitedReActRuntime runtime = runtime(
                context -> Mono.just(decisions.get(plannerIndex.getAndIncrement())),
                (validated, context) -> {
                    toolCalls.incrementAndGet();
                    return Mono.just(success("one"));
                }, traces);

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(3, 3, 1, 100, 100),
                List.of(read("repository.search")), new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.REPEATED_ACTION);
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(toolCalls).hasValue(1);
        assertThat(traces.traces("turn-react").getLast().actionDigest())
                .isEqualTo(action.digest());
    }

    @Test
    void writeCapabilityIsRejectedBeforeExecutor() {
        ReactAction action = new ReactAction("memory.write", Map.of());
        AtomicInteger toolCalls = new AtomicInteger();
        InMemoryReactTraceRepository traces = new InMemoryReactTraceRepository();
        LimitedReActRuntime runtime = runtime(
                context -> Mono.just(continueWith(action)),
                (validated, context) -> {
                    toolCalls.incrementAndGet();
                    return Mono.just(success("should not run"));
                }, traces);

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(3, 3, 2, 100, 100),
                List.of(write("memory.write")), new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.ACTION_REJECTED);
        assertThat(result.toolCalls()).isZero();
        assertThat(toolCalls).hasValue(0);
        assertThat(traces.traces("turn-react").getFirst().reasonCode())
                .isEqualTo("WRITE_TOOL_APPROVAL_REQUIRED");
    }

    @Test
    void twoConsecutiveRoundsWithoutNewInformationStopAtThirdRound() {
        List<ReactAction> actions = List.of(
                new ReactAction("read.one", Map.of()),
                new ReactAction("read.two", Map.of()),
                new ReactAction("read.three", Map.of()));
        AtomicInteger plannerIndex = new AtomicInteger();
        LimitedReActRuntime runtime = runtime(
                context -> Mono.just(continueWith(actions.get(plannerIndex.getAndIncrement()))),
                (validated, context) -> Mono.just(success("identical evidence")),
                new InMemoryReactTraceRepository());

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(3, 3, 1, 100, 100),
                actions.stream().map(value -> read(value.capabilityId())).toList(),
                new CancellationToken())).block();

        assertThat(result.terminationReason()).isEqualTo(ReactTerminationReason.NO_NEW_INFORMATION);
        assertThat(result.rounds()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(3);
    }

    @Test
    void deadlineCancellationAndPlannerUsageAllTerminateBeforeAction() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ReactPlanner planner = context -> {
            plannerCalls.incrementAndGet();
            return Mono.just(new ReactPlannerDecision(
                    ReactDecision.CONTINUE, "goal", "MORE",
                    new ReactAction("repository.search", Map.of()), null,
                    5, 0));
        };
        ReactActionExecutor executor = (action, context) -> {
            toolCalls.incrementAndGet();
            return Mono.just(success("unused"));
        };

        ReactRunResult expired = runtime(planner, executor,
                new InMemoryReactTraceRepository()).run(request(
                        TurnExecutionMode.AGENT,
                        budgetAt(3, 3, 2, 100, 100, NOW),
                        List.of(read("repository.search")),
                        new CancellationToken())).block();

        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        ReactRunResult cancelled = runtime(planner, executor,
                new InMemoryReactTraceRepository()).run(request(
                        TurnExecutionMode.AGENT,
                        budget(3, 3, 2, 100, 100),
                        List.of(read("repository.search")), cancellation)).block();

        ReactRunResult exhausted = runtime(planner, executor,
                new InMemoryReactTraceRepository()).run(request(
                        TurnExecutionMode.AGENT,
                        budget(3, 3, 2, 5, 100),
                        List.of(read("repository.search")),
                        new CancellationToken())).block();

        assertThat(expired.terminationReason()).isEqualTo(ReactTerminationReason.DEADLINE_EXCEEDED);
        assertThat(cancelled.terminationReason()).isEqualTo(ReactTerminationReason.CANCELLED);
        assertThat(exhausted.terminationReason()).isEqualTo(ReactTerminationReason.TOKEN_BUDGET_EXHAUSTED);
        assertThat(plannerCalls).hasValue(1);
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void delegateAgentDecisionFailsClosedInReadOnlyMvp() {
        LimitedReActRuntime runtime = runtime(
                context -> Mono.just(new ReactPlannerDecision(
                        ReactDecision.DELEGATE_AGENT, "goal", "REMOTE_REQUIRED", null, null)),
                (action, context) -> Mono.just(success("unused")),
                new InMemoryReactTraceRepository());

        ReactRunResult result = runtime.run(request(
                TurnExecutionMode.AGENT, budget(3, 3, 2, 100, 100),
                List.of(read("repository.search")), new CancellationToken())).block();

        assertThat(result.terminationReason())
                .isEqualTo(ReactTerminationReason.DELEGATION_NOT_ENABLED);
        assertThat(result.finalDecision()).isEqualTo(ReactDecision.DELEGATE_AGENT);
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void actionDigestIsMapOrderIndependentAndTypePreserving() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", List.of("x", true));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", List.of("x", true));
        second.put("a", 1L);

        assertThat(new ReactAction("read", first).digest())
                .isEqualTo(new ReactAction("read", second).digest());
        assertThat(new ReactAction("read", Map.of("value", "1")).digest())
                .isNotEqualTo(new ReactAction("read", Map.of("value", 1)).digest());
    }

    private LimitedReActRuntime runtime(
            ReactPlanner planner,
            ReactActionExecutor executor,
            InMemoryReactTraceRepository traces) {
        return new LimitedReActRuntime(
                planner,
                new ActionProposalValidator(),
                executor,
                new DefaultObservationNormalizer(64),
                new TerminationPolicy(),
                traces,
                clock);
    }

    private ReactRunRequest request(
            TurnExecutionMode mode,
            ExecutionBudget budget,
            List<CapabilityDescriptor> descriptors,
            CancellationToken cancellation) {
        return request(mode, budget, descriptors, List.of(), cancellation);
    }

    private ReactRunRequest request(
            TurnExecutionMode mode,
            ExecutionBudget budget,
            List<CapabilityDescriptor> descriptors,
            List<ReactSkillCandidate> skillCandidates,
            CancellationToken cancellation) {
        return new ReactRunRequest(
                new ReactInvocationScope(
                        "turn-react", "trace-react", "tenant", "user", "website",
                        Set.of("repository:read"), "snapshot-v1", false),
                "inspect dependencies",
                mode,
                budget,
                descriptors,
                skillCandidates,
                cancellation,
                "planner/v1");
    }

    private ExecutionBudget budget(
            int rounds,
            int toolCalls,
            int repeated,
            long tokens,
            long cost) {
        return budgetAt(rounds, toolCalls, repeated, tokens, cost, NOW.plusSeconds(30));
    }

    private static ExecutionBudget budgetAt(
            int rounds,
            int toolCalls,
            int repeated,
            long tokens,
            long cost,
            Instant deadline) {
        return new ExecutionBudget(
                8, rounds, 0, toolCalls, 0, 0, rounds, repeated,
                tokens, cost, deadline);
    }

    private static ReactPlannerDecision continueWith(ReactAction action) {
        return new ReactPlannerDecision(
                ReactDecision.CONTINUE, "inspect", "MORE_EVIDENCE", action, null);
    }

    private static ReactPlannerDecision finalizeDecision() {
        return new ReactPlannerDecision(
                ReactDecision.FINALIZE, "inspect", "EVIDENCE_COMPLETE", null, "done");
    }

    private static RawReactObservation success(String summary) {
        return new RawReactObservation(
                RawReactObservation.Status.SUCCESS,
                Map.of("value", "same"),
                summary,
                null,
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                false,
                false,
                1,
                1);
    }

    private static CapabilityDescriptor read(String id) {
        return descriptor(id, CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.READ,
                CapabilityDescriptor.ApprovalRequirement.NONE);
    }

    private static CapabilityDescriptor write(String id) {
        return descriptor(id, CapabilityDescriptor.Kind.WRITE_TOOL,
                CapabilityDescriptor.SideEffect.WRITE,
                CapabilityDescriptor.ApprovalRequirement.ALWAYS);
    }

    private static CapabilityDescriptor promptSkill(String id) {
        return descriptor(id, CapabilityDescriptor.Kind.PROMPT_SKILL,
                CapabilityDescriptor.SideEffect.NONE,
                CapabilityDescriptor.ApprovalRequirement.NONE);
    }

    private static CapabilityDescriptor descriptor(
            String id,
            CapabilityDescriptor.Kind kind,
            CapabilityDescriptor.SideEffect sideEffect,
            CapabilityDescriptor.ApprovalRequirement approval) {
        return new CapabilityDescriptor(
                id,
                "1",
                kind,
                "tests",
                new CapabilityDescriptor.Schema(
                        Map.of("query", CapabilityDescriptor.ValueType.STRING),
                        Set.of(), true, Set.of()),
                CapabilityDescriptor.Schema.empty(),
                Set.of("repository:read"),
                sideEffect,
                approval,
                Duration.ofSeconds(2),
                CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(1),
                Set.of(CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                kind == CapabilityDescriptor.Kind.PROMPT_SKILL
                        ? CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL
                        : CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL,
                "test://" + id,
                CapabilityDescriptor.Health.HEALTHY,
                false,
                1,
                CapabilityDescriptor.NetworkPolicy.denied(),
                Set.of(),
                new CapabilityDescriptor.CostPolicy(1, 10),
                CapabilityDescriptor.CachePolicy.disabled(),
                kind == CapabilityDescriptor.Kind.WRITE_TOOL
                        ? new CapabilityDescriptor.IdempotencyPolicy(true, true)
                        : CapabilityDescriptor.IdempotencyPolicy.none());
    }
}
