package com.meguri.core.react;

import com.meguri.core.agent.CancellationToken;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.execution.ExecutionBudget;
import com.meguri.core.execution.TurnExecutionMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReactRunRequest(
        ReactInvocationScope scope,
        String goal,
        TurnExecutionMode executionMode,
        ExecutionBudget budget,
        List<CapabilityDescriptor> exposedCapabilities,
        CancellationToken cancellation,
        String plannerRevision) {

    public ReactRunRequest {
        scope = Objects.requireNonNull(scope, "scope");
        goal = ReactValues.required(goal, "goal");
        executionMode = Objects.requireNonNull(executionMode, "executionMode");
        budget = Objects.requireNonNull(budget, "budget").limitedReactV1();
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
        plannerRevision = ReactValues.required(plannerRevision, "plannerRevision");
        List<CapabilityDescriptor> copy = exposedCapabilities == null
                ? List.of() : List.copyOf(exposedCapabilities);
        Map<String, CapabilityDescriptor> unique = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : copy) {
            Objects.requireNonNull(descriptor, "exposed capability");
            CapabilityDescriptor previous = unique.putIfAbsent(descriptor.id(), descriptor);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate exposed capability: " + descriptor.id());
            }
        }
        exposedCapabilities = List.copyOf(unique.values());
    }
}
