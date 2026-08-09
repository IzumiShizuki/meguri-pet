package com.meguri.core.react;

import com.meguri.core.execution.ExecutionBudget;
import com.meguri.core.execution.TurnExecutionMode;

import java.time.Clock;
import java.util.Optional;

/** Deterministic stop policy evaluated before planning, before actions, and after observations. */
public final class TerminationPolicy {
    private static final int MAX_CONSECUTIVE_NO_NEW_INFORMATION = 2;
    private static final int MAX_CONSECUTIVE_CAPABILITY_FAILURES = 2;

    public Optional<ReactTerminationReason> beforePlanning(
            ReactRunRequest request,
            RuntimeCounters counters,
            Clock clock) {
        if (request.executionMode() != TurnExecutionMode.AGENT) {
            return Optional.of(ReactTerminationReason.MODE_NOT_AGENT);
        }
        if (request.cancellation().isCancelled()) {
            return Optional.of(ReactTerminationReason.CANCELLED);
        }
        if (!clock.instant().isBefore(request.budget().deadlineAt())) {
            return Optional.of(ReactTerminationReason.DEADLINE_EXCEEDED);
        }
        if (counters.rounds() >= request.budget().maxRounds()) {
            return Optional.of(ReactTerminationReason.MAX_ROUNDS);
        }
        if (counters.modelCalls() >= request.budget().maxModelCalls()) {
            return Optional.of(ReactTerminationReason.MAX_MODEL_CALLS);
        }
        if (counters.tokensUsed() >= request.budget().maxTokens()) {
            return Optional.of(ReactTerminationReason.TOKEN_BUDGET_EXHAUSTED);
        }
        if (counters.costUnitsUsed() >= request.budget().maxCostUnits()) {
            return Optional.of(ReactTerminationReason.COST_BUDGET_EXHAUSTED);
        }
        return Optional.empty();
    }

    public Optional<ReactTerminationReason> beforeAction(
            ReactRunRequest request,
            RuntimeCounters counters,
            int priorActionProposals,
            NormalizedReactObservation cached,
            Clock clock) {
        if (request.cancellation().isCancelled()) {
            return Optional.of(ReactTerminationReason.CANCELLED);
        }
        if (!clock.instant().isBefore(request.budget().deadlineAt())) {
            return Optional.of(ReactTerminationReason.DEADLINE_EXCEEDED);
        }
        if (cached != null && !cached.successful()) {
            return Optional.of(ReactTerminationReason.REPEATED_FAILED_ACTION);
        }
        if (priorActionProposals >= request.budget().repeatedActionLimit()) {
            return Optional.of(ReactTerminationReason.REPEATED_ACTION);
        }
        if (cached == null && counters.toolCalls() >= request.budget().maxToolCalls()) {
            return Optional.of(ReactTerminationReason.MAX_TOOL_CALLS);
        }
        if (counters.tokensUsed() >= request.budget().maxTokens()) {
            return Optional.of(ReactTerminationReason.TOKEN_BUDGET_EXHAUSTED);
        }
        if (counters.costUnitsUsed() >= request.budget().maxCostUnits()) {
            return Optional.of(ReactTerminationReason.COST_BUDGET_EXHAUSTED);
        }
        return Optional.empty();
    }

    public Optional<ReactTerminationReason> afterObservation(
            ExecutionBudget budget,
            RuntimeCounters counters,
            NormalizedReactObservation observation) {
        if (observation.evidenceSufficient()) {
            return Optional.of(ReactTerminationReason.EVIDENCE_SUFFICIENT);
        }
        if (counters.tokensUsed() >= budget.maxTokens()) {
            return Optional.of(ReactTerminationReason.TOKEN_BUDGET_EXHAUSTED);
        }
        if (counters.costUnitsUsed() >= budget.maxCostUnits()) {
            return Optional.of(ReactTerminationReason.COST_BUDGET_EXHAUSTED);
        }
        if (counters.consecutiveNoNewInformation()
                >= MAX_CONSECUTIVE_NO_NEW_INFORMATION) {
            return Optional.of(ReactTerminationReason.NO_NEW_INFORMATION);
        }
        if (counters.consecutiveFailures()
                >= MAX_CONSECUTIVE_CAPABILITY_FAILURES) {
            return Optional.of(ReactTerminationReason.CONSECUTIVE_CAPABILITY_FAILURES);
        }
        return Optional.empty();
    }

    public record RuntimeCounters(
            int rounds,
            int modelCalls,
            int toolCalls,
            long tokensUsed,
            long costUnitsUsed,
            int consecutiveNoNewInformation,
            int consecutiveFailures) {
    }
}
