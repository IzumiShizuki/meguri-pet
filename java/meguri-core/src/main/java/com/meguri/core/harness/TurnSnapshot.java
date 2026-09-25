package com.meguri.core.harness;

import com.meguri.core.dto.ChatResponse;
import com.meguri.core.runtime.TurnRecord;

import java.time.Instant;

/** Immutable read model returned across the Turn Runtime interface. */
public record TurnSnapshot(
        String turnId,
        String userId,
        String clientId,
        String sessionId,
        String status,
        String stage,
        long lastSequence,
        Instant acceptedAt,
        Instant deadlineAt,
        HarnessManifest manifest,
        ChatResponse result,
        String error) {
    public static TurnSnapshot from(TurnRecord record, long lastSequence) {
        return new TurnSnapshot(
                record.getTurnId(),
                record.getRequest().getUserId(),
                record.getRequest().getClientId(),
                record.getRequest().getSessionId(),
                record.statusValue(),
                record.getStage().wireValue(),
                lastSequence,
                record.getAcceptedAt(),
                record.getDeadlineAt(),
                record.getManifest(),
                record.getResult(),
                record.getError());
    }

    public boolean terminal() {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }
}
