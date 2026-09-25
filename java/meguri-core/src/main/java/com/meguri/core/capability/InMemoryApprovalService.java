package com.meguri.core.capability;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryApprovalService implements ApprovalService {
    private final Map<String, Approval> approvals = new LinkedHashMap<>();
    private final Clock clock;

    public InMemoryApprovalService() {
        this(Clock.systemUTC());
    }

    public InMemoryApprovalService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Approval request(ToolProposal proposal, CapabilityDescriptor descriptor) {
        return request(proposal, descriptor, "unbound");
    }

    @Override
    public synchronized Approval request(
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            String snapshotId) {
        String id = UUID.randomUUID().toString();
        Approval approval = new Approval(
                id, proposal.operationId(), descriptor.id(), Decision.PENDING,
                null, Instant.now(clock), null,
                CapabilityDescriptor.required(snapshotId, "snapshotId"),
                proposal.turnId(), proposal.traceId(), proposal.tenantId(),
                proposal.userId(), proposal.clientId(), descriptor.version(),
                proposal.idempotencyKey(), CapabilityDigest.sha256(proposal.input()));
        approvals.put(id, approval);
        return approval;
    }

    @Override
    public synchronized Approval resolve(String approvalId, Decision decision, String actor) {
        if (decision == null || decision == Decision.PENDING) {
            throw new IllegalArgumentException("approval resolution must be ACCEPT, DECLINE, or CANCEL");
        }
        Approval current = findApproval(approvalId).orElseThrow(() -> new IllegalArgumentException("unknown approval"));
        if (current.decision() != Decision.PENDING) {
            if (current.decision() == decision) return current;
            throw new IllegalStateException("approval is already resolved");
        }
        Approval resolved = new Approval(current.approvalId(), current.operationId(), current.capabilityId(),
                decision, CapabilityDescriptor.required(actor, "actor"),
                current.createdAt(), Instant.now(clock), current.snapshotId(),
                current.turnId(), current.traceId(), current.tenantId(),
                current.userId(), current.clientId(), current.capabilityVersion(),
                current.idempotencyKey(), current.requestDigest());
        approvals.put(approvalId, resolved);
        return resolved;
    }

    @Override
    public synchronized Optional<Approval> findApproval(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    @Override
    public synchronized List<Approval> approvals() {
        return List.copyOf(approvals.values());
    }
}
