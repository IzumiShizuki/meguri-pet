package com.meguri.core.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Server-owned ceiling for HTTP-created agent tasks. Requests may narrow these
 * values but can never widen them.
 */
public record AgentRuntimePolicy(
        Duration maximumDeadline,
        AgentTaskContext.Budget maximumBudget,
        Set<String> allowedCapabilities) {

    public AgentRuntimePolicy {
        Objects.requireNonNull(maximumDeadline, "maximumDeadline");
        Objects.requireNonNull(maximumBudget, "maximumBudget");
        if (maximumDeadline.isZero() || maximumDeadline.isNegative()) {
            throw new IllegalArgumentException("maximumDeadline must be positive");
        }
        allowedCapabilities = Set.copyOf(
                allowedCapabilities == null ? Set.of() : allowedCapabilities);
    }

    public static AgentRuntimePolicy defaults(AgentRuntimeFactory.Config config) {
        return new AgentRuntimePolicy(
                Duration.ofMinutes(10),
                new AgentTaskContext.Budget(
                        16_000,
                        16,
                        new java.math.BigDecimal("10.00"),
                        4,
                        8),
                config.allowedCapabilities());
    }
}
