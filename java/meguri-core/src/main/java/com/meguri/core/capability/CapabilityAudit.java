package com.meguri.core.capability;

import java.time.Instant;
import java.util.List;

public interface CapabilityAudit {
    void record(Event event);
    List<Event> events();

    record Event(
            String eventId,
            Instant occurredAt,
            String turnId,
            String traceId,
            String snapshotId,
            String tenantId,
            String userId,
            String clientId,
            String capabilityId,
            String capabilityVersion,
            String operationId,
            String idempotencyKey,
            String requestDigest,
            String resultDigest,
            String approvalId,
            ApprovalService.Decision approvalDecision,
            String phase,
            int attempt,
            String status,
            String errorCode,
            long durationMs,
            boolean externalResult,
            boolean retryable) { }
}
