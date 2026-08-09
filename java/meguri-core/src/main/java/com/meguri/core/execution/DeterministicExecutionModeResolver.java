package com.meguri.core.execution;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure rule resolver. A classifier can propose a candidate, but this class owns
 * the final downgrade and budget decision.
 */
public final class DeterministicExecutionModeResolver implements ExecutionModeResolver {
    private static final Set<String> AGENT_INTENTS = Set.of(
            "agent", "execute", "multi_source_investigation", "repository_analysis",
            "tool_required", "remote_agent");
    private static final Set<String> THINK_INTENTS = Set.of(
            "architecture", "complex_analysis", "complex_reasoning", "compare",
            "long_form_analysis", "plan_answer");
    private static final Set<String> FAST_INTENTS = Set.of(
            "greeting", "emotion", "companionship", "rewrite", "simple_explanation",
            "current_context_follow_up");

    private final ExecutionBudgetPolicy budgets;
    private final Duration minimumThinkTime;
    private final Duration minimumAgentTime;
    private final Clock clock;

    public DeterministicExecutionModeResolver(
            ExecutionBudgetPolicy budgets,
            Duration minimumThinkTime,
            Duration minimumAgentTime,
            Clock clock) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.minimumThinkTime = positive(minimumThinkTime, "minimumThinkTime");
        this.minimumAgentTime = positive(minimumAgentTime, "minimumAgentTime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExecutionModeDecision resolve(
            CurrentTurnSignals signals,
            ExecutionModeResolutionContext context) {
        Objects.requireNonNull(signals, "signals");
        Objects.requireNonNull(context, "context");
        List<String> reasons = new ArrayList<>();
        TurnExecutionMode selected = select(signals, reasons);
        TurnExecutionMode resolved = applyServerDowngrades(selected, signals, context, reasons);
        ExecutionBudget budget = budgets.clip(
                resolved, context.requestedBudget(), context.parentBudget());

        if (resolved == TurnExecutionMode.AGENT && !agentBudgetUsable(budget)) {
            reasons.add("SERVER_DOWNGRADE_AGENT_BUDGET_UNAVAILABLE");
            resolved = fallbackThinkOrFast(signals, context, reasons);
            budget = budgets.clip(resolved, context.requestedBudget(), context.parentBudget());
        }
        if (resolved == TurnExecutionMode.THINK && budget.maxModelCalls() == 0) {
            reasons.add("SERVER_DOWNGRADE_THINK_MODEL_BUDGET_UNAVAILABLE");
            resolved = TurnExecutionMode.FAST;
            budget = budgets.clip(resolved, context.requestedBudget(), context.parentBudget());
        }

        boolean reactEligible = resolved == TurnExecutionMode.AGENT
                && context.availability().agentEnabled()
                && context.availability().readOnlyCapabilitiesAvailable()
                && signals.toolCapableClient()
                && agentBudgetUsable(budget)
                && budget.remainingMillis(clock) > 0;
        return new ExecutionModeDecision(
                resolved,
                reasons,
                budget,
                signals.userRequestedMode() != null,
                reactEligible);
    }

    private TurnExecutionMode select(CurrentTurnSignals signals, List<String> reasons) {
        if (signals.userRequestedMode() != null) {
            reasons.add("USER_EXPLICIT_" + signals.userRequestedMode());
            return signals.userRequestedMode();
        }
        if (signals.skillRequiredMode() != null) {
            reasons.add("SKILL_REQUIRED_" + signals.skillRequiredMode());
            return signals.skillRequiredMode();
        }
        if (signals.externalObservationRequired() || signals.multiStepExecutionRequired()
                || containsAny(signals, AGENT_INTENTS)) {
            reasons.add("DETERMINISTIC_AGENT_SIGNAL");
            return TurnExecutionMode.AGENT;
        }
        if (containsAny(signals, THINK_INTENTS)) {
            reasons.add("DETERMINISTIC_THINK_INTENT");
            return TurnExecutionMode.THINK;
        }
        if (containsAny(signals, FAST_INTENTS)) {
            reasons.add("DETERMINISTIC_FAST_INTENT");
            return TurnExecutionMode.FAST;
        }
        if (signals.classifierCandidateMode() != null) {
            reasons.add("CLASSIFIER_CANDIDATE_" + signals.classifierCandidateMode());
            return signals.classifierCandidateMode();
        }
        reasons.add("SERVER_DEFAULT_FAST");
        return TurnExecutionMode.FAST;
    }

    private TurnExecutionMode applyServerDowngrades(
            TurnExecutionMode selected,
            CurrentTurnSignals signals,
            ExecutionModeResolutionContext context,
            List<String> reasons) {
        if (selected == TurnExecutionMode.AGENT) {
            if (!context.availability().agentEnabled()) {
                reasons.add("SERVER_DOWNGRADE_AGENT_DISABLED");
                return fallbackThinkOrFast(signals, context, reasons);
            }
            if (!signals.toolCapableClient()) {
                reasons.add("SERVER_DOWNGRADE_CLIENT_TOOL_UNSUPPORTED");
                return fallbackThinkOrFast(signals, context, reasons);
            }
            if (!context.availability().readOnlyCapabilitiesAvailable()) {
                reasons.add("SERVER_DOWNGRADE_READ_CAPABILITY_UNAVAILABLE");
                return fallbackThinkOrFast(signals, context, reasons);
            }
            if (signals.hasIntent("remote_agent")
                    && (!signals.remoteAgentCapableClient()
                    || !context.availability().remoteAgentsAvailable())) {
                reasons.add("SERVER_DOWNGRADE_REMOTE_AGENT_UNAVAILABLE");
                return fallbackThinkOrFast(signals, context, reasons);
            }
            if (!hasTime(signals, context, minimumAgentTime)) {
                reasons.add("SERVER_DOWNGRADE_AGENT_DEADLINE_INSUFFICIENT");
                return fallbackThinkOrFast(signals, context, reasons);
            }
        }
        if (selected == TurnExecutionMode.THINK) {
            if (!context.availability().thinkEnabled()) {
                reasons.add("SERVER_DOWNGRADE_THINK_DISABLED");
                return TurnExecutionMode.FAST;
            }
            if (!hasTime(signals, context, minimumThinkTime)) {
                reasons.add("SERVER_DOWNGRADE_THINK_DEADLINE_INSUFFICIENT");
                return TurnExecutionMode.FAST;
            }
        }
        return selected;
    }

    private TurnExecutionMode fallbackThinkOrFast(
            CurrentTurnSignals signals,
            ExecutionModeResolutionContext context,
            List<String> reasons) {
        if (context.availability().thinkEnabled()
                && hasTime(signals, context, minimumThinkTime)) {
            reasons.add("SERVER_FALLBACK_THINK");
            return TurnExecutionMode.THINK;
        }
        reasons.add("SERVER_FALLBACK_FAST");
        return TurnExecutionMode.FAST;
    }

    private boolean hasTime(
            CurrentTurnSignals signals,
            ExecutionModeResolutionContext context,
            Duration required) {
        long actual = context.parentBudget().remainingMillis(clock);
        long declared = signals.remainingDeadlineMillis();
        return Math.min(actual, declared) >= required.toMillis();
    }

    private static boolean containsAny(CurrentTurnSignals signals, Set<String> candidates) {
        return signals.intentTags().stream().anyMatch(candidates::contains);
    }

    private static boolean agentBudgetUsable(ExecutionBudget budget) {
        return budget.maxModelCalls() > 0 && budget.maxRounds() > 0
                && budget.maxToolCalls() > 0 && budget.repeatedActionLimit() > 0;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
