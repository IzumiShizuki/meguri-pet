package com.meguri.core.capability;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CapabilityPersistence {
    void saveDefinition(CapabilityDescriptor descriptor);
    void saveBinding(Binding binding);
    void saveExecution(Execution execution);
    void saveApproval(ApprovalService.Approval approval);
    void saveAudit(CapabilityAudit.Event event);
    Optional<Execution> findOperation(String tenantId, String capabilityId, String idempotencyKey);
    List<CapabilityAudit.Event> auditEvents(String traceId);

    record Binding(
            String tenantId, String capabilityId, String version, boolean enabled, boolean draining,
            CapabilityDescriptor.Health health, Instant updatedAt) { }

    record Execution(
            String executionId, String operationId, String tenantId, String userId, String capabilityId,
            String version, String idempotencyKey, CapabilityResult result, Instant startedAt, Instant completedAt) { }
}
