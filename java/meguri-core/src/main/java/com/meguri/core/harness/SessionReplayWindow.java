package com.meguri.core.harness;

public record SessionReplayWindow(
        String sessionId,
        long oldestAvailableSequence,
        long latestSequence) {
    public SessionReplayWindow {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (oldestAvailableSequence < 0 || latestSequence < 0
                || latestSequence > 0 && oldestAvailableSequence > latestSequence) {
            throw new IllegalArgumentException("invalid replay window");
        }
    }

    public boolean cursorExpired(long afterSequence) {
        return oldestAvailableSequence > 1
                && afterSequence < oldestAvailableSequence - 1;
    }
}
