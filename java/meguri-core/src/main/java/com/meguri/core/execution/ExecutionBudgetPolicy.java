package com.meguri.core.execution;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Intersects requested, parent, and mode-owned ceilings. */
public final class ExecutionBudgetPolicy {
    private final Map<TurnExecutionMode, ExecutionBudgetLimits> modeLimits;
    private final Clock clock;

    public ExecutionBudgetPolicy(
            Map<TurnExecutionMode, ExecutionBudgetLimits> modeLimits,
            Clock clock) {
        Objects.requireNonNull(modeLimits, "modeLimits");
        EnumMap<TurnExecutionMode, ExecutionBudgetLimits> copy =
                new EnumMap<>(TurnExecutionMode.class);
        copy.putAll(modeLimits);
        for (TurnExecutionMode mode : TurnExecutionMode.values()) {
            Objects.requireNonNull(copy.get(mode), "missing budget limits for " + mode);
        }
        this.modeLimits = Map.copyOf(copy);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Conservative bootstrap ceilings for wiring before measured environment
     * profiles are supplied. These are safety limits, not TTFT SLO claims.
     * Remote agents remain disabled in the read-only v1 profile.
     */
    public static ExecutionBudgetPolicy safeBootstrapDefaults(Clock clock) {
        return new ExecutionBudgetPolicy(Map.of(
                TurnExecutionMode.FAST,
                new ExecutionBudgetLimits(
                        4, 1, 1, 0, 0, 0, 0, 0,
                        8_192, 2_048, Duration.ofSeconds(45)),
                TurnExecutionMode.THINK,
                new ExecutionBudgetLimits(
                        6, 2, 1, 0, 0, 0, 0, 0,
                        32_768, 8_192, Duration.ofMinutes(3)),
                TurnExecutionMode.AGENT,
                new ExecutionBudgetLimits(
                        8, 6, 2, 4, 0, 0, 6, 2,
                        32_768, 16_384, Duration.ofMinutes(3))), clock);
    }

    public ExecutionBudget clip(
            TurnExecutionMode mode,
            ExecutionBudget requested,
            ExecutionBudget parent) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(parent, "parent");
        ExecutionBudget desired = requested == null ? parent : requested;
        ExecutionBudget modeCeiling = modeLimits.get(mode)
                .at(clock.instant(), parent.deadlineAt());
        ExecutionBudget clipped = desired.clipTo(parent).clipTo(modeCeiling);
        return switch (mode) {
            case FAST, THINK -> clipped.withoutAgentLoop();
            case AGENT -> clipped.limitedReactV1();
        };
    }
}
