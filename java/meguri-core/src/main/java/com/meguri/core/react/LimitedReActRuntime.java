package com.meguri.core.react;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only, at-most-three-round ReAct core. It never invokes a capability
 * directly: production wiring must use {@link ReactActionExecutor} to delegate
 * to the existing frozen Capability Runtime.
 */
public final class LimitedReActRuntime {
    private final ReactPlanner planner;
    private final ActionProposalValidator validator;
    private final ReactActionExecutor executor;
    private final ObservationNormalizer normalizer;
    private final TerminationPolicy terminationPolicy;
    private final ReactTraceRepository traces;
    private final Clock clock;

    public LimitedReActRuntime(
            ReactPlanner planner,
            ActionProposalValidator validator,
            ReactActionExecutor executor,
            ObservationNormalizer normalizer,
            TerminationPolicy terminationPolicy,
            ReactTraceRepository traces,
            Clock clock) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.terminationPolicy = Objects.requireNonNull(terminationPolicy, "terminationPolicy");
        this.traces = Objects.requireNonNull(traces, "traces");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Mono<ReactRunResult> run(ReactRunRequest request) {
        Objects.requireNonNull(request, "request");
        State state = new State();
        return Mono.defer(() -> advance(request, state));
    }

    private Mono<ReactRunResult> advance(ReactRunRequest request, State state) {
        Optional<ReactTerminationReason> stop = terminationPolicy.beforePlanning(
                request, state.counters(), clock);
        if (stop.isPresent()) {
            return finish(request, state, stop.get(), decisionFor(stop.get()), null);
        }

        int roundIndex = state.rounds + 1;
        Instant startedAt = clock.instant();
        state.rounds++;
        state.modelCalls++;
        ReactPlanningContext planningContext = new ReactPlanningContext(
                request.scope(), request.goal(), roundIndex,
                remaining(request.budget().maxModelCalls(), state.modelCalls),
                remaining(request.budget().maxToolCalls(), state.toolCalls),
                remaining(request.budget().maxTokens(), state.tokensUsed),
                remaining(request.budget().maxCostUnits(), state.costUnitsUsed),
                request.budget().deadlineAt(), state.observations);

        return Mono.defer(() -> planner.plan(planningContext))
                .map(PlannerOutcome::success)
                .onErrorResume(error -> Mono.just(PlannerOutcome.failure()))
                .defaultIfEmpty(PlannerOutcome.failure())
                .flatMap(outcome -> outcome.failed()
                        ? plannerFailed(request, state, roundIndex, startedAt)
                        : handleDecision(request, state, outcome.decision(),
                                roundIndex, startedAt));
    }

    private Mono<ReactRunResult> handleDecision(
            ReactRunRequest request,
            State state,
            ReactPlannerDecision decision,
            int roundIndex,
            Instant startedAt) {
        state.tokensUsed = saturatedAdd(state.tokensUsed, decision.tokensUsed());
        state.costUnitsUsed = saturatedAdd(state.costUnitsUsed, decision.costUnits());
        return switch (decision.decision()) {
            case FINALIZE -> appendAndFinish(
                    request, state,
                    trace(request, decision, roundIndex, startedAt,
                            null, null, ReactTerminationReason.MODEL_FINALIZED),
                    ReactTerminationReason.MODEL_FINALIZED,
                    ReactDecision.FINALIZE,
                    decision.finalAnswer());
            case WAIT_FOR_APPROVAL -> appendAndFinish(
                    request, state,
                    trace(request, decision, roundIndex, startedAt,
                            null, null, ReactTerminationReason.WAITING_FOR_APPROVAL),
                    ReactTerminationReason.WAITING_FOR_APPROVAL,
                    ReactDecision.WAIT_FOR_APPROVAL,
                    null);
            case DELEGATE_AGENT -> appendAndFinish(
                    request, state,
                    trace(request, decision, roundIndex, startedAt,
                            null, null, ReactTerminationReason.DELEGATION_NOT_ENABLED),
                    ReactTerminationReason.DELEGATION_NOT_ENABLED,
                    ReactDecision.DELEGATE_AGENT,
                    null);
            case FAIL -> appendAndFinish(
                    request, state,
                    trace(request, decision, roundIndex, startedAt,
                            null, null, ReactTerminationReason.MODEL_FAILED),
                    ReactTerminationReason.MODEL_FAILED,
                    ReactDecision.FAIL,
                    null);
            case CONTINUE -> handleAction(
                    request, state, decision, roundIndex, startedAt);
        };
    }

    private Mono<ReactRunResult> handleAction(
            ReactRunRequest request,
            State state,
            ReactPlannerDecision decision,
            int roundIndex,
            Instant startedAt) {
        ActionProposalValidator.ValidationResult validation = validator.validate(
                decision.action(), request.exposedCapabilities(),
                remaining(request.budget().maxCostUnits(), state.costUnitsUsed));
        if (!validation.accepted()) {
            ReactPlannerDecision rejected = new ReactPlannerDecision(
                    ReactDecision.CONTINUE, decision.goal(),
                    validation.reasonCode(), decision.action(), null,
                    decision.tokensUsed(), decision.costUnits());
            return appendAndFinish(
                    request, state,
                    trace(request, rejected, roundIndex, startedAt,
                            null, validation.action(), ReactTerminationReason.ACTION_REJECTED),
                    ReactTerminationReason.ACTION_REJECTED,
                    ReactDecision.FAIL,
                    null);
        }

        ValidatedReactAction action = validation.action();
        String digest = action.actionDigest();
        int priorProposals = state.actionProposals.getOrDefault(digest, 0);
        NormalizedReactObservation cached = state.actionCache.get(digest);
        Optional<ReactTerminationReason> stop = terminationPolicy.beforeAction(
                request, state.counters(), priorProposals, cached, clock);
        if (stop.isPresent()) {
            return appendAndFinish(
                    request, state,
                    trace(request, decision, roundIndex, startedAt,
                            null, action, stop.get()),
                    stop.get(), ReactDecision.FAIL, null);
        }
        state.actionProposals.put(digest, priorProposals + 1);

        if (cached != null) {
            return processObservation(request, state, decision, action,
                    cached.asReused(), roundIndex, startedAt);
        }

        state.toolCalls++;
        ReactExecutionContext executionContext = new ReactExecutionContext(
                request.scope(), roundIndex, digest, request.budget().deadlineAt(),
                remaining(request.budget().maxTokens(), state.tokensUsed),
                remaining(request.budget().maxCostUnits(), state.costUnitsUsed),
                request.cancellation());
        return Mono.defer(() -> executor.execute(action, executionContext))
                .switchIfEmpty(Mono.just(RawReactObservation.executionFailure()))
                .onErrorReturn(RawReactObservation.executionFailure())
                .map(normalizer::normalize)
                .flatMap(observation -> processObservation(
                        request, state, decision, action, observation,
                        roundIndex, startedAt));
    }

    private Mono<ReactRunResult> processObservation(
            ReactRunRequest request,
            State state,
            ReactPlannerDecision decision,
            ValidatedReactAction action,
            NormalizedReactObservation observation,
            int roundIndex,
            Instant startedAt) {
        boolean newInformation = state.informationDigests.add(
                observation.informationDigest());
        state.consecutiveNoNewInformation = newInformation
                ? 0 : state.consecutiveNoNewInformation + 1;
        state.consecutiveFailures = observation.successful()
                ? 0 : state.consecutiveFailures + 1;
        state.tokensUsed = saturatedAdd(state.tokensUsed, observation.tokensUsed());
        state.costUnitsUsed = saturatedAdd(state.costUnitsUsed, observation.costUnits());
        state.observations.add(observation);
        state.actionCache.put(action.actionDigest(), observation);

        Optional<ReactTerminationReason> stop = terminationPolicy.afterObservation(
                request.budget(), state.counters(), observation);
        ReactRoundTrace roundTrace = trace(
                request, decision, roundIndex, startedAt,
                observation, action, stop.orElse(null));
        if (stop.isPresent()) {
            return appendAndFinish(request, state, roundTrace,
                    stop.get(), decisionFor(stop.get()), null);
        }
        return traces.append(roundTrace).then(Mono.defer(() -> advance(request, state)));
    }

    private Mono<ReactRunResult> plannerFailed(
            ReactRunRequest request,
            State state,
            int roundIndex,
            Instant startedAt) {
        ReactPlannerDecision failure = new ReactPlannerDecision(
                ReactDecision.FAIL, request.goal(), "PLANNER_FAILED", null, null);
        return appendAndFinish(
                request, state,
                trace(request, failure, roundIndex, startedAt,
                        null, null, ReactTerminationReason.PLANNER_FAILED),
                ReactTerminationReason.PLANNER_FAILED,
                ReactDecision.FAIL,
                null);
    }

    private Mono<ReactRunResult> appendAndFinish(
            ReactRunRequest request,
            State state,
            ReactRoundTrace trace,
            ReactTerminationReason reason,
            ReactDecision decision,
            String finalAnswer) {
        return traces.append(trace)
                .then(Mono.defer(() -> finish(
                        request, state, reason, decision, finalAnswer)));
    }

    private Mono<ReactRunResult> finish(
            ReactRunRequest request,
            State state,
            ReactTerminationReason reason,
            ReactDecision decision,
            String finalAnswer) {
        ReactRunResult result = new ReactRunResult(
                request.scope().turnId(), reason, decision, finalAnswer,
                state.rounds, state.modelCalls, state.toolCalls,
                state.tokensUsed, state.costUnitsUsed, state.observations);
        return traces.complete(ReactExecutionSummary.from(result)).thenReturn(result);
    }

    private ReactRoundTrace trace(
            ReactRunRequest request,
            ReactPlannerDecision decision,
            int roundIndex,
            Instant startedAt,
            NormalizedReactObservation observation,
            ValidatedReactAction action,
            ReactTerminationReason terminationReason) {
        ReactAction proposed = decision.action();
        return new ReactRoundTrace(
                request.scope().turnId(),
                roundIndex,
                request.plannerRevision(),
                decision.decision(),
                decision.goal(),
                decision.reasonCode(),
                action == null
                        ? proposed == null ? null : proposed.digest()
                        : action.actionDigest(),
                action == null
                        ? proposed == null ? null : proposed.capabilityId()
                        : action.descriptor().id(),
                observation == null ? null : observation.summary(),
                observation == null ? null : observation.informationDigest(),
                observation != null && observation.reused(),
                observation == null ? null : observation.successful(),
                terminationReason,
                startedAt,
                clock.instant());
    }

    private static ReactDecision decisionFor(ReactTerminationReason reason) {
        return switch (reason) {
            case MODEL_FINALIZED, EVIDENCE_SUFFICIENT -> ReactDecision.FINALIZE;
            case WAITING_FOR_APPROVAL -> ReactDecision.WAIT_FOR_APPROVAL;
            case DELEGATION_NOT_ENABLED -> ReactDecision.DELEGATE_AGENT;
            default -> ReactDecision.FAIL;
        };
    }

    private static int remaining(int maximum, int used) {
        return Math.max(0, maximum - used);
    }

    private static long remaining(long maximum, long used) {
        return Math.max(0L, maximum - Math.min(maximum, used));
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static final class State {
        private int rounds;
        private int modelCalls;
        private int toolCalls;
        private long tokensUsed;
        private long costUnitsUsed;
        private int consecutiveNoNewInformation;
        private int consecutiveFailures;
        private final List<NormalizedReactObservation> observations = new ArrayList<>();
        private final Set<String> informationDigests = new HashSet<>();
        private final Map<String, Integer> actionProposals = new HashMap<>();
        private final Map<String, NormalizedReactObservation> actionCache = new HashMap<>();

        private TerminationPolicy.RuntimeCounters counters() {
            return new TerminationPolicy.RuntimeCounters(
                    rounds, modelCalls, toolCalls, tokensUsed, costUnitsUsed,
                    consecutiveNoNewInformation, consecutiveFailures);
        }
    }

    private record PlannerOutcome(ReactPlannerDecision decision, boolean failed) {
        private static PlannerOutcome success(ReactPlannerDecision decision) {
            return new PlannerOutcome(Objects.requireNonNull(decision, "decision"), false);
        }

        private static PlannerOutcome failure() {
            return new PlannerOutcome(null, true);
        }
    }
}
