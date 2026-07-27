package com.meguri.core.harness;

/** Replay cursor for one identity-scoped session. A turn filter is optional. */
public record EventCursor(
        String sessionId,
        long afterSequence,
        String turnId,
        String userId,
        String clientId) {
    public EventCursor {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session_id must not be blank");
        }
        if (afterSequence < 0) throw new IllegalArgumentException("after_sequence must be non-negative");
        sessionId = sessionId.trim();
        turnId = turnId == null || turnId.isBlank() ? null : turnId.trim();
        userId = userId == null || userId.isBlank() ? null : userId.trim();
        clientId = clientId == null || clientId.isBlank() ? null : clientId.trim();
        if ((userId == null) != (clientId == null)) {
            throw new IllegalArgumentException("user_id and client_id must be supplied together");
        }
    }

    public EventCursor(String sessionId, long afterSequence) {
        this(sessionId, afterSequence, null, null, null);
    }

    public EventCursor(String sessionId, long afterSequence, String turnId) {
        this(sessionId, afterSequence, turnId, null, null);
    }
}
