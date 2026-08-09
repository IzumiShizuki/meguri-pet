package com.meguri.core.execution;

import java.util.Objects;

public record ExecutionModeResolutionContext(
        ExecutionBudget requestedBudget,
        ExecutionBudget parentBudget,
        ExecutionModeAvailability availability) {

    public ExecutionModeResolutionContext {
        parentBudget = Objects.requireNonNull(parentBudget, "parentBudget");
        availability = Objects.requireNonNull(availability, "availability");
    }
}
