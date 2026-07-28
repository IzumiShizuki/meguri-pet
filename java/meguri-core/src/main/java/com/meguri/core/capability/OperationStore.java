package com.meguri.core.capability;

import java.time.Instant;
import java.util.Optional;

public interface OperationStore {
    Claim claim(String tenantId, String userId, String capabilityId, String operationId, String idempotencyKey);

    default Claim claim(
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            String snapshotId) {
        return claim(
                proposal.tenantId(),
                proposal.userId(),
                descriptor.id(),
                proposal.operationId(),
                proposal.idempotencyKey());
    }

    void complete(String operationId, CapabilityResult result);
    Optional<Entry> find(String operationId);

    enum State { STARTED, COMPLETED, UNKNOWN }
    record Entry(
            String operationId,
            String idempotencyKey,
            State state,
            CapabilityResult result,
            Instant updatedAt,
            String requestDigest) {
        public Entry(
                String operationId,
                String idempotencyKey,
                State state,
                CapabilityResult result,
                Instant updatedAt) {
            this(operationId, idempotencyKey, state, result, updatedAt, null);
        }
    }
    record Claim(Entry entry, boolean created) { }
}
