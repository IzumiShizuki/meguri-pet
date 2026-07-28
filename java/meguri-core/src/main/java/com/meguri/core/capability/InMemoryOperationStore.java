package com.meguri.core.capability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryOperationStore implements OperationStore {
    private final Map<Scope, Entry> byScope = new LinkedHashMap<>();
    private final Map<String, Scope> byOperation = new LinkedHashMap<>();

    @Override
    public synchronized Claim claim(
            String tenantId, String userId, String capabilityId, String operationId, String idempotencyKey) {
        return claim(tenantId, userId, capabilityId, operationId, idempotencyKey, null);
    }

    @Override
    public synchronized Claim claim(
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            String snapshotId) {
        return claim(
                proposal.tenantId(),
                proposal.userId(),
                descriptor.id(),
                proposal.operationId(),
                proposal.idempotencyKey(),
                CapabilityDigest.sha256(proposal.input()));
    }

    private Claim claim(
            String tenantId,
            String userId,
            String capabilityId,
            String operationId,
            String idempotencyKey,
            String requestDigest) {
        String deduplicationKey = idempotencyKey == null ? "operation:" + operationId : "key:" + idempotencyKey;
        Scope scope = new Scope(tenantId, userId, capabilityId, deduplicationKey);
        Entry existing = byScope.get(scope);
        if (existing != null) return new Claim(existing, false);
        if (byOperation.containsKey(operationId)) throw new IllegalArgumentException("operation_id already exists");
        Entry created = new Entry(
                operationId, idempotencyKey, State.STARTED, null,
                Instant.now(), requestDigest);
        byScope.put(scope, created);
        byOperation.put(operationId, scope);
        return new Claim(created, true);
    }

    @Override
    public synchronized void complete(String operationId, CapabilityResult result) {
        Scope scope = byOperation.get(operationId);
        if (scope == null) throw new IllegalArgumentException("unknown operation");
        State state = result.status() == CapabilityResult.Status.UNKNOWN_OUTCOME ? State.UNKNOWN : State.COMPLETED;
        Entry current = byScope.get(scope);
        byScope.put(scope, new Entry(
                operationId, current.idempotencyKey(), state, result,
                Instant.now(), current.requestDigest()));
    }

    @Override
    public synchronized Optional<Entry> find(String operationId) {
        Scope scope = byOperation.get(operationId);
        return scope == null ? Optional.empty() : Optional.of(byScope.get(scope));
    }

    private record Scope(String tenantId, String userId, String capabilityId, String idempotencyKey) { }
}
