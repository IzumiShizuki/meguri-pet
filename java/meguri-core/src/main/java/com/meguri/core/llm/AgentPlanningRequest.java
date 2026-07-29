package com.meguri.core.llm;

import java.util.List;
import java.util.Set;

/** Bounded server-side planning input for selecting an already-authorized remote Agent. */
public record AgentPlanningRequest(
        ProviderRequest context,
        List<AgentCandidate> candidates,
        String requestedMode) {
    public AgentPlanningRequest {
        if (context == null) throw new IllegalArgumentException("context is required");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        requestedMode = requestedMode == null ? "FAST" : requestedMode.trim().toUpperCase();
    }

    public record AgentCandidate(
            String agentId,
            Set<String> allowedCapabilities,
            String resultSchemaId) {
        public AgentCandidate {
            agentId = required(agentId, "agentId");
            allowedCapabilities = allowedCapabilities == null
                    ? Set.of() : Set.copyOf(allowedCapabilities);
            resultSchemaId = required(resultSchemaId, "resultSchemaId");
        }
    }

    public record Decision(
            String agentId,
            String taskBrief,
            ExecutionPreference executionPreference) {
        public Decision {
            agentId = required(agentId, "agentId");
            taskBrief = required(taskBrief, "taskBrief");
            executionPreference = executionPreference == null
                    ? ExecutionPreference.AWAIT : executionPreference;
        }
    }

    public enum ExecutionPreference { AWAIT, DURABLE_ASYNC }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
