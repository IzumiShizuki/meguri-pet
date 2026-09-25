package com.meguri.core.harness.capability;

import com.meguri.core.harness.capability.CapabilityRegistry.Effect;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic local ledger; a durable adapter can retain the same contract. */
public final class InMemoryEffectLedger implements EffectLedger {
    private final Map<String, Receipt> receipts = new ConcurrentHashMap<>();
    private final Map<Scope, String> scopes = new ConcurrentHashMap<>();

    @Override
    public synchronized Claim begin(
            String turnId,
            String principalId,
            String capabilityId,
            String capabilityVersion,
            String idempotencyKey,
            Effect effect,
            boolean approvalGranted,
            String inputHash) {
        Scope scope = new Scope(principalId, capabilityId, idempotencyKey);
        String existingId = scopes.get(scope);
        if (existingId != null) {
            Receipt existing = receipts.get(existingId);
            if (!Objects.equals(existing.inputHash(), inputHash)) {
                throw new CapabilityExecutionException("effect idempotency payload conflict");
            }
            return new Claim(existing, false);
        }
        Receipt receipt = new Receipt(
                "effect_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                turnId, principalId, capabilityId, capabilityVersion, idempotencyKey, effect,
                approvalGranted, inputHash,
                null, Status.STARTED, null, Instant.now(), null);
        receipts.put(receipt.receiptId(), receipt);
        scopes.put(scope, receipt.receiptId());
        return new Claim(receipt, true);
    }

    @Override
    public synchronized Receipt complete(String receiptId, String outputHash) {
        Receipt current = required(receiptId);
        if (current.status() != Status.STARTED) return current;
        Receipt completed = new Receipt(
                current.receiptId(), current.turnId(), current.principalId(), current.capabilityId(),
                current.capabilityVersion(), current.idempotencyKey(), current.effect(),
                current.approvalGranted(), current.inputHash(), outputHash, Status.COMPLETED, null,
                current.startedAt(), Instant.now());
        receipts.put(receiptId, completed);
        return completed;
    }

    @Override
    public synchronized Receipt fail(String receiptId, String errorCode) {
        Receipt current = required(receiptId);
        if (current.status() != Status.STARTED) return current;
        Receipt failed = new Receipt(
                current.receiptId(), current.turnId(), current.principalId(), current.capabilityId(),
                current.capabilityVersion(), current.idempotencyKey(), current.effect(),
                current.approvalGranted(), current.inputHash(), null, Status.FAILED,
                errorCode == null || errorCode.isBlank() ? "capability_failed" : errorCode,
                current.startedAt(), Instant.now());
        receipts.put(receiptId, failed);
        return failed;
    }

    @Override
    public List<Receipt> receipts(String turnId) {
        return receipts.values().stream()
                .filter(receipt -> receipt.turnId().equals(turnId))
                .sorted(java.util.Comparator.comparing(Receipt::startedAt))
                .toList();
    }

    private Receipt required(String receiptId) {
        Receipt receipt = receipts.get(receiptId);
        if (receipt == null) throw new IllegalArgumentException("unknown effect receipt: " + receiptId);
        return receipt;
    }

    private record Scope(String principalId, String capabilityId, String idempotencyKey) { }
}
