package com.meguri.core.capability;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApprovalService {
    Approval request(ToolProposal proposal, CapabilityDescriptor descriptor);
    default Approval request(
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            String snapshotId) {
        return request(proposal, descriptor);
    }
    Approval resolve(String approvalId, Decision decision, String actor);
    Optional<Approval> findApproval(String approvalId);
    List<Approval> approvals();

    enum Decision { PENDING, ACCEPT, DECLINE, CANCEL }

    record Approval(
            String approvalId,
            String operationId,
            String capabilityId,
            Decision decision,
            String actor,
            Instant createdAt,
            Instant resolvedAt,
            String snapshotId,
            String turnId,
            String traceId,
            String tenantId,
            String userId,
            String clientId,
            String capabilityVersion,
            String idempotencyKey,
            String requestDigest) {
        public Approval(
                String approvalId,
                String operationId,
                String capabilityId,
                Decision decision,
                String actor,
                Instant createdAt,
                Instant resolvedAt) {
            this(approvalId, operationId, capabilityId, decision, actor,
                    createdAt, resolvedAt, "unbound", "unbound", "unbound",
                    "unbound", "unbound", "unbound", null, null,
                    CapabilityDigest.sha256(java.util.Map.of()));
        }
    }
}
