package com.meguri.core.harness.capability;

import com.meguri.core.harness.capability.CapabilityRegistry.Effect;

import java.time.Instant;
import java.util.List;

/** Content-free receipts for observable capability effects. */
public interface EffectLedger {
    Claim begin(String turnId, String principalId, String capabilityId, String capabilityVersion,
                String idempotencyKey, Effect effect, boolean approvalGranted, String inputHash);

    Receipt complete(String receiptId, String outputHash);

    Receipt fail(String receiptId, String errorCode);

    List<Receipt> receipts(String turnId);

    enum Status { STARTED, COMPLETED, FAILED }

    /** Atomic result of claiming one idempotency scope. */
    record Claim(Receipt receipt, boolean created) { }

    record Receipt(
            String receiptId,
            String turnId,
            String principalId,
            String capabilityId,
            String capabilityVersion,
            String idempotencyKey,
            Effect effect,
            boolean approvalGranted,
            String inputHash,
            String outputHash,
            Status status,
            String errorCode,
            Instant startedAt,
            Instant finishedAt) { }
}
