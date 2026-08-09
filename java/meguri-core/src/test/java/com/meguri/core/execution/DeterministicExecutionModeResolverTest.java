package com.meguri.core.execution;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicExecutionModeResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ExecutionBudgetPolicy budgets = new ExecutionBudgetPolicy(Map.of(
            TurnExecutionMode.FAST, limits(4, 1, 1, 0, 0, 0, 0, 0),
            TurnExecutionMode.THINK, limits(7, 2, 1, 0, 0, 0, 0, 0),
            TurnExecutionMode.AGENT, limits(10, 4, 3, 5, 2, 2, 9, 4)), clock);
    private final DeterministicExecutionModeResolver resolver =
            new DeterministicExecutionModeResolver(
                    budgets, Duration.ofSeconds(2), Duration.ofSeconds(5), clock);

    @Test
    void explicitFastCannotBeUpgradedBySkillClassifierOrAgentSignals() {
        CurrentTurnSignals signals = signals(
                TurnExecutionMode.FAST, TurnExecutionMode.AGENT,
                TurnExecutionMode.AGENT, List.of("remote_agent"),
                true, true, true, true, 30_000);

        ExecutionModeDecision decision = resolver.resolve(signals, context(allAvailable()));

        assertThat(decision.mode()).isEqualTo(TurnExecutionMode.FAST);
        assertThat(decision.userExplicit()).isTrue();
        assertThat(decision.reasonCodes()).containsExactly("USER_EXPLICIT_FAST");
        assertThat(decision.reactEligible()).isFalse();
        assertThat(decision.budget().maxToolCalls()).isZero();
        assertThat(decision.budget().maxRounds()).isZero();
    }

    @Test
    void skillPolicyPrecedesDeterministicAndClassifierCandidates() {
        CurrentTurnSignals signals = signals(
                null, TurnExecutionMode.THINK, TurnExecutionMode.AGENT,
                List.of("tool_required"), true, true, true, true, 30_000);

        ExecutionModeDecision decision = resolver.resolve(signals, context(allAvailable()));

        assertThat(decision.mode()).isEqualTo(TurnExecutionMode.THINK);
        assertThat(decision.reasonCodes()).containsExactly("SKILL_REQUIRED_THINK");
        assertThat(decision.budget().maxToolCalls()).isZero();
        assertThat(decision.reactEligible()).isFalse();
    }

    @Test
    void agentRuleClipsRequestedParentAndV1RoundCeilings() {
        CurrentTurnSignals signals = signals(
                null, null, TurnExecutionMode.FAST, List.of(),
                true, true, true, true, 30_000);
        ExecutionBudget parent = budget(8, NOW.plusSeconds(20));
        ExecutionBudget requested = budget(20, NOW.plusSeconds(60));
        ExecutionModeResolutionContext context = new ExecutionModeResolutionContext(
                requested, parent, allAvailable());

        ExecutionModeDecision decision = resolver.resolve(signals, context);

        assertThat(decision.mode()).isEqualTo(TurnExecutionMode.AGENT);
        assertThat(decision.reasonCodes()).containsExactly("DETERMINISTIC_AGENT_SIGNAL");
        assertThat(decision.budget().maxRounds()).isEqualTo(3);
        assertThat(decision.budget().maxToolCalls()).isEqualTo(5);
        assertThat(decision.budget().deadlineAt()).isEqualTo(parent.deadlineAt());
        assertThat(decision.reactEligible()).isTrue();
    }

    @Test
    void unavailableToolClientDowngradesAgentWithoutGrantingAuthority() {
        CurrentTurnSignals signals = signals(
                TurnExecutionMode.AGENT, null, null, List.of("tool_required"),
                true, true, false, false, 30_000);

        ExecutionModeDecision decision = resolver.resolve(signals, context(allAvailable()));

        assertThat(decision.mode()).isEqualTo(TurnExecutionMode.THINK);
        assertThat(decision.reasonCodes()).contains(
                "USER_EXPLICIT_AGENT",
                "SERVER_DOWNGRADE_CLIENT_TOOL_UNSUPPORTED",
                "SERVER_FALLBACK_THINK");
        assertThat(decision.userExplicit()).isTrue();
        assertThat(decision.reactEligible()).isFalse();
        assertThat(decision.budget().maxToolCalls()).isZero();
    }

    @Test
    void insufficientDeadlineDeterministicallyFallsBackToFast() {
        CurrentTurnSignals signals = signals(
                null, null, null, List.of("complex_analysis"),
                false, false, true, false, 500);

        ExecutionModeDecision decision = resolver.resolve(signals, context(allAvailable()));

        assertThat(decision.mode()).isEqualTo(TurnExecutionMode.FAST);
        assertThat(decision.reasonCodes()).containsExactly(
                "DETERMINISTIC_THINK_INTENT",
                "SERVER_DOWNGRADE_THINK_DEADLINE_INSUFFICIENT");
    }

    @Test
    void safeBootstrapFactoryKeepsFastAndThinkToolFreeAndAgentAtThreeRounds() {
        ExecutionBudgetPolicy defaults = ExecutionBudgetPolicy.safeBootstrapDefaults(clock);
        ExecutionBudget parent = budget(100, NOW.plusSeconds(600));

        assertThat(defaults.clip(TurnExecutionMode.FAST, parent, parent).maxToolCalls()).isZero();
        assertThat(defaults.clip(TurnExecutionMode.THINK, parent, parent).maxToolCalls()).isZero();
        assertThat(defaults.clip(TurnExecutionMode.AGENT, parent, parent).maxRounds()).isEqualTo(3);
        assertThat(defaults.clip(TurnExecutionMode.AGENT, parent, parent).maxRemoteAgents()).isZero();
    }

    private ExecutionModeResolutionContext context(ExecutionModeAvailability availability) {
        ExecutionBudget parent = budget(20, NOW.plusSeconds(30));
        return new ExecutionModeResolutionContext(parent, parent, availability);
    }

    private static ExecutionModeAvailability allAvailable() {
        return new ExecutionModeAvailability(true, true, true, true);
    }

    private static CurrentTurnSignals signals(
            TurnExecutionMode user,
            TurnExecutionMode skill,
            TurnExecutionMode classifier,
            List<String> intents,
            boolean observation,
            boolean multiStep,
            boolean tools,
            boolean remote,
            long remainingMillis) {
        return new CurrentTurnSignals(user, skill, classifier, intents,
                observation, multiStep, tools, remote, remainingMillis);
    }

    private static ExecutionBudget budget(int value, Instant deadline) {
        return new ExecutionBudget(value, value, value, value, value, value,
                value, value, value * 1_000L, value * 1_000L, deadline);
    }

    private static ExecutionBudgetLimits limits(
            int stages,
            int models,
            int retrievals,
            int tools,
            int agents,
            int depth,
            int rounds,
            int repeated) {
        return new ExecutionBudgetLimits(stages, models, retrievals, tools,
                agents, depth, rounds, repeated, 100_000, 100_000,
                Duration.ofMinutes(1));
    }
}
