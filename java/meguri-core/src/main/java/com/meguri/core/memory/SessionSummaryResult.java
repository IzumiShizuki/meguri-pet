package com.meguri.core.memory;

/** The persisted session-summary acknowledgement from the authoritative provider. */
public record SessionSummaryResult(
        String status,
        String userId,
        String clientId,
        String sessionId,
        String summary,
        int messageCount) {
    public static SessionSummaryResult unavailable(String userId, String clientId, String sessionId) {
        return new SessionSummaryResult("unavailable", userId, clientId, sessionId, "", 0);
    }
}
