package com.meguri.core.persona.runtime;

import java.time.Instant;

/** Immutable turn-scoped interaction input frozen before persona reduction. */
public record InteractionStateSnapshot(String turnId, String sessionId, String userId,
                                       InteractionState state, long version, Instant createdAt) {
    public InteractionStateSnapshot {
        if (turnId == null || turnId.isBlank() || sessionId == null || sessionId.isBlank()
                || userId == null || userId.isBlank()) throw new IllegalArgumentException("interaction scope is required");
        if (state == null || version < 1 || createdAt == null) throw new IllegalArgumentException("interaction snapshot is invalid");
    }
    public boolean sameFrozenValue(InteractionStateSnapshot other) {
        return other != null && turnId.equals(other.turnId) && sessionId.equals(other.sessionId)
                && userId.equals(other.userId) && state.equals(other.state) && version == other.version;
    }
}
