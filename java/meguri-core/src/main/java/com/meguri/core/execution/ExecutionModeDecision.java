package com.meguri.core.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** The frozen decision written into a Turn plan/trace. */
public record ExecutionModeDecision(
        @JsonProperty("mode") TurnExecutionMode mode,
        @JsonProperty("reason_codes") List<String> reasonCodes,
        @JsonProperty("budget") ExecutionBudget budget,
        @JsonProperty("user_explicit") boolean userExplicit,
        @JsonProperty("react_eligible") boolean reactEligible) {

    public ExecutionModeDecision {
        mode = Objects.requireNonNull(mode, "mode");
        budget = Objects.requireNonNull(budget, "budget");
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (reasonCodes != null) {
            for (String reason : reasonCodes) {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reasonCodes must not contain blanks");
                }
                reasons.add(reason.trim());
            }
        }
        if (reasons.isEmpty()) throw new IllegalArgumentException("reasonCodes must not be empty");
        reasonCodes = Collections.unmodifiableList(new ArrayList<>(reasons));
        if (reactEligible && mode != TurnExecutionMode.AGENT) {
            throw new IllegalArgumentException("only AGENT can be ReAct eligible");
        }
    }
}
