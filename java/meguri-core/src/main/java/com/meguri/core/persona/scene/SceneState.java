package com.meguri.core.persona.scene;

import java.time.Instant;

public record SceneState(String conversationId, Type type, Phase phase, double confidence, int evidenceCount,
                         Instant startedAt, Instant expiresAt, Instant changedAt, Instant cooldownUntil,
                         long version) {
    public SceneState(String conversationId, Type type, Phase phase, double confidence, int evidenceCount,
                      Instant startedAt, Instant expiresAt, Instant changedAt, Instant cooldownUntil) {
        this(conversationId, type, phase, confidence, evidenceCount, startedAt, expiresAt, changedAt,
                cooldownUntil, 0);
    }
    public SceneState {
        if (conversationId == null || conversationId.isBlank()) throw new IllegalArgumentException("conversationId is required");
        if (type == null || phase == null) throw new IllegalArgumentException("scene type and phase are required");
        if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be in [0,1]");
        if (evidenceCount < 0 || version < 0) throw new IllegalArgumentException("scene counters must not be negative");
    }
    public enum Type { WORK_ASSIST, CASUAL, COMFORT, CELEBRATION, REUNION, STUDY, EVENT }
    public enum Phase { CANDIDATE, ACTIVE, ENDING, CLOSED }
}
