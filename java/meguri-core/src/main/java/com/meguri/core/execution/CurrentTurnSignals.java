package com.meguri.core.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic inputs assembled by the server. Clients may request a mode but
 * must never submit this complete signal object as an authority decision.
 */
public record CurrentTurnSignals(
        @JsonProperty("user_requested_mode") TurnExecutionMode userRequestedMode,
        @JsonProperty("skill_required_mode") TurnExecutionMode skillRequiredMode,
        @JsonProperty("classifier_candidate_mode") TurnExecutionMode classifierCandidateMode,
        @JsonProperty("intent_tags") List<String> intentTags,
        @JsonProperty("external_observation_required") boolean externalObservationRequired,
        @JsonProperty("multi_step_execution_required") boolean multiStepExecutionRequired,
        @JsonProperty("tool_capable_client") boolean toolCapableClient,
        @JsonProperty("remote_agent_capable_client") boolean remoteAgentCapableClient,
        @JsonProperty("remaining_deadline_millis") long remainingDeadlineMillis) {

    public CurrentTurnSignals {
        if (remainingDeadlineMillis < 0) {
            throw new IllegalArgumentException("remainingDeadlineMillis must be non-negative");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (intentTags != null) {
            for (String tag : intentTags) {
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException("intentTags must not contain blanks");
                }
                normalized.add(tag.trim().toLowerCase(Locale.ROOT));
            }
        }
        intentTags = Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    public boolean hasIntent(String value) {
        return value != null && intentTags.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
