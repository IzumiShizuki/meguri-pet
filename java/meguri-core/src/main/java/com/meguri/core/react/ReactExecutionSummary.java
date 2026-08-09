package com.meguri.core.react;

/** Terminal persistence shape; final answer and full observation objects stay out of trace storage. */
public record ReactExecutionSummary(
        String turnId,
        ReactTerminationReason terminationReason,
        ReactDecision finalDecision,
        int rounds,
        int modelCalls,
        int toolCalls,
        long tokensUsed,
        long costUnitsUsed) {

    public static ReactExecutionSummary from(ReactRunResult result) {
        return new ReactExecutionSummary(
                result.turnId(), result.terminationReason(), result.finalDecision(),
                result.rounds(), result.modelCalls(), result.toolCalls(),
                result.tokensUsed(), result.costUnitsUsed());
    }
}
