package com.meguri.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record InvokeAgentProposal(
        String agentId,
        String taskBrief,
        Map<String, String> references,
        InvocationMode mode,
        boolean required,
        String idempotencySuffix,
        Instant deadline,
        AgentTaskContext.Budget budget,
        Set<String> allowedCapabilities,
        ResultSchema resultSchema,
        boolean allowSensitiveResult,
        Duration pollInterval,
        int maxPollAttempts) {

    public InvokeAgentProposal {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
        if (taskBrief == null || taskBrief.isBlank()) throw new IllegalArgumentException("taskBrief is required");
        references = Map.copyOf(references == null ? Map.of() : references);
        Objects.requireNonNull(mode, "mode");
        if (idempotencySuffix == null || idempotencySuffix.isBlank()) {
            throw new IllegalArgumentException("idempotencySuffix is required");
        }
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(budget, "budget");
        allowedCapabilities = Set.copyOf(allowedCapabilities == null ? Set.of() : allowedCapabilities);
        Objects.requireNonNull(resultSchema, "resultSchema");
        pollInterval = pollInterval == null ? Duration.ofMillis(100) : pollInterval;
        if (pollInterval.isNegative() || pollInterval.isZero() || maxPollAttempts < 1) {
            throw new IllegalArgumentException("poll bounds are invalid");
        }
    }

    public enum InvocationMode {
        AWAIT, PARALLEL_AWAIT, DURABLE_ASYNC
    }

    public record ResultSchema(String schemaId, Map<String, ValueType> requiredFields) {
        public ResultSchema {
            if (schemaId == null || schemaId.isBlank()) throw new IllegalArgumentException("schemaId is required");
            requiredFields = Map.copyOf(requiredFields == null ? Map.of() : requiredFields);
        }
    }

    public enum ValueType {
        STRING, NUMBER, BOOLEAN, OBJECT, ARRAY
    }
}
