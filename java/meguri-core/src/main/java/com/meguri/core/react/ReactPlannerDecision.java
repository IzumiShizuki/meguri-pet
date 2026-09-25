package com.meguri.core.react;

import java.util.Objects;

/** Structured planner output. Private reasoning text is intentionally absent. */
public record ReactPlannerDecision(
        ReactDecision decision,
        String goal,
        String reasonCode,
        ReactAction action,
        String finalAnswer,
        long tokensUsed,
        long costUnits) {

    public ReactPlannerDecision {
        decision = Objects.requireNonNull(decision, "decision");
        goal = ReactValues.required(goal, "goal");
        reasonCode = ReactValues.required(reasonCode, "reasonCode");
        finalAnswer = ReactValues.optional(finalAnswer);
        if (decision == ReactDecision.CONTINUE && action == null) {
            throw new IllegalArgumentException("CONTINUE requires an action");
        }
        if (decision != ReactDecision.CONTINUE && action != null) {
            throw new IllegalArgumentException(decision + " must not carry a capability action");
        }
        if (decision == ReactDecision.FINALIZE && finalAnswer == null) {
            throw new IllegalArgumentException("FINALIZE requires finalAnswer");
        }
        if (decision != ReactDecision.FINALIZE && finalAnswer != null) {
            throw new IllegalArgumentException("only FINALIZE may carry finalAnswer");
        }
        if (tokensUsed < 0 || costUnits < 0) {
            throw new IllegalArgumentException("planner usage must be non-negative");
        }
    }

    public ReactPlannerDecision(
            ReactDecision decision,
            String goal,
            String reasonCode,
            ReactAction action,
            String finalAnswer) {
        this(decision, goal, reasonCode, action, finalAnswer, 0, 0);
    }
}
