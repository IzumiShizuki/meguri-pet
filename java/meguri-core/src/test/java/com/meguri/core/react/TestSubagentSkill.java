package com.meguri.core.react;

import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, test-only stand-in for a read-only subagent skill.
 *
 * <p>It models a two-step bounded investigation: propose one local read and
 * then finalize from its observation. Production wiring never registers this
 * fixture, so it cannot become an end-user capability by accident.</p>
 */
public final class TestSubagentSkill implements ReactPlanner, ReactActionExecutor {
    public static final String CAPABILITY_ID = "test.subagent.solve";

    private final AtomicInteger plannerCalls = new AtomicInteger();
    private final AtomicInteger actionCalls = new AtomicInteger();

    @Override
    public Mono<ReactPlannerDecision> plan(ReactPlanningContext context) {
        plannerCalls.incrementAndGet();
        if (context.observations().isEmpty()) {
            return Mono.just(new ReactPlannerDecision(
                    ReactDecision.CONTINUE,
                    context.goal(),
                    "TEST_SUBAGENT_NEEDS_ONE_READ",
                    new ReactAction(CAPABILITY_ID, Map.of(
                            "question", context.goal(),
                            "scenario", scenario(context.goal()))),
                    null,
                    1,
                    1));
        }
        return Mono.just(new ReactPlannerDecision(
                ReactDecision.FINALIZE,
                context.goal(),
                "TEST_SUBAGENT_EVIDENCE_COMPLETE",
                null,
                "test-subagent answered the " + scenario(context.goal()) + " scenario",
                1,
                0));
    }

    @Override
    public Mono<RawReactObservation> execute(
            ValidatedReactAction action,
            ReactExecutionContext context) {
        actionCalls.incrementAndGet();
        String scenario = scenario(String.valueOf(action.action().arguments().get("question")));
        return Mono.just(new RawReactObservation(
                RawReactObservation.Status.SUCCESS,
                Map.of("scenario", scenario, "source", "test-subagent"),
                "test-subagent evidence for " + scenario + " scenario",
                null,
                ObservationTrustLabel.UNTRUSTED_CAPABILITY_RESULT,
                false,
                false,
                1,
                1));
    }

    public int plannerCalls() {
        return plannerCalls.get();
    }

    public int actionCalls() {
        return actionCalls.get();
    }

    private static String scenario(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return normalized.contains("困难") || normalized.contains("hard")
                || normalized.contains("证明") || normalized.contains("推理")
                ? "hard-reasoning" : "ordinary-conversation";
    }
}
