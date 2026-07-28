package com.meguri.core.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record AgentTaskContext(
        String tenantId,
        String userId,
        String parentTaskId,
        String traceId,
        String spanId,
        String idempotencyKey,
        Instant deadline,
        String capabilitySnapshotVersion,
        CancellationToken cancellation,
        Budget budget,
        int depth,
        Set<String> allowedCapabilities,
        String taskBrief,
        Map<String, String> references) {

    public AgentTaskContext {
        tenantId = required(tenantId, "tenantId");
        userId = required(userId, "userId");
        traceId = required(traceId, "traceId");
        spanId = required(spanId, "spanId");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(deadline, "deadline");
        capabilitySnapshotVersion = capabilitySnapshotVersion == null
                || capabilitySnapshotVersion.isBlank()
                ? "capability:legacy"
                : capabilitySnapshotVersion.trim();
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(budget, "budget");
        if (depth < 0 || depth > budget.maxDepth()) throw new IllegalArgumentException("depth exceeds maxDepth");
        allowedCapabilities = Set.copyOf(allowedCapabilities == null ? Set.of() : allowedCapabilities);
        taskBrief = required(taskBrief, "taskBrief");
        references = Map.copyOf(references == null ? Map.of() : references);
    }

    public AgentTaskContext(
            String tenantId,
            String userId,
            String parentTaskId,
            String traceId,
            String spanId,
            String idempotencyKey,
            Instant deadline,
            CancellationToken cancellation,
            Budget budget,
            int depth,
            Set<String> allowedCapabilities,
            String taskBrief,
            Map<String, String> references) {
        this(tenantId, userId, parentTaskId, traceId, spanId, idempotencyKey,
                deadline, "capability:legacy", cancellation, budget, depth,
                allowedCapabilities, taskBrief, references);
    }

    public AgentTaskContext child(ChildScope requested) {
        Objects.requireNonNull(requested, "requested");
        int childDepth = depth + 1;
        if (childDepth > budget.maxDepth()) throw new AgentPolicyException("maximum agent depth exceeded");
        if (requested.deadline().isAfter(deadline)) throw new AgentPolicyException("child deadline cannot expand");
        if (!allowedCapabilities.containsAll(requested.allowedCapabilities())) {
            throw new AgentPolicyException("child capabilities cannot expand");
        }
        Budget childBudget = budget.narrow(requested.budget());
        return new AgentTaskContext(
                tenantId,
                userId,
                requested.parentTaskId(),
                traceId,
                required(requested.spanId(), "spanId"),
                idempotencyKey + "/" + required(requested.idempotencySuffix(), "idempotencySuffix"),
                requested.deadline(),
                capabilitySnapshotVersion,
                cancellation.child(),
                childBudget,
                childDepth,
                requested.allowedCapabilities(),
                requested.taskBrief(),
                requested.references());
    }

    public record Budget(
            long maxTokens,
            int maxToolCalls,
            BigDecimal maxCost,
            int maxDepth,
            int maxChildren) {
        public Budget {
            if (maxTokens < 0 || maxToolCalls < 0 || maxCost == null || maxCost.signum() < 0
                    || maxDepth < 0 || maxChildren < 0) {
                throw new IllegalArgumentException("budgets must be non-negative");
            }
        }

        public Budget narrow(Budget requested) {
            Objects.requireNonNull(requested, "requested budget");
            if (requested.maxTokens > maxTokens || requested.maxToolCalls > maxToolCalls
                    || requested.maxCost.compareTo(maxCost) > 0 || requested.maxDepth > maxDepth
                    || requested.maxChildren > maxChildren) {
                throw new AgentPolicyException("child budget cannot expand");
            }
            return requested;
        }
    }

    public record ChildScope(
            String parentTaskId,
            String spanId,
            String idempotencySuffix,
            Instant deadline,
            Budget budget,
            Set<String> allowedCapabilities,
            String taskBrief,
            Map<String, String> references) {
        public ChildScope {
            Objects.requireNonNull(deadline, "deadline");
            allowedCapabilities = Set.copyOf(allowedCapabilities == null ? Set.of() : allowedCapabilities);
            references = Map.copyOf(references == null ? Map.of() : references);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
