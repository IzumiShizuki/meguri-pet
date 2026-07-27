package com.meguri.core.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
/** The persisted session-summary acknowledgement from the authoritative provider. */
public record SessionSummaryResult(
        String status,
        String userId,
        String clientId,
        String sessionId,
        String summary,
        int messageCount,
        @JsonProperty("structured_candidates") List<?> structuredCandidates,
        @JsonProperty("candidate_ids") List<String> candidateIds,
        @JsonProperty("candidate_status") String candidateStatus) {
    public SessionSummaryResult(String status, String userId, String clientId, String sessionId,
                                 String summary, int messageCount) {
        this(status, userId, clientId, sessionId, summary, messageCount, List.of(), List.of(), "audit_only");
    }

    public static SessionSummaryResult unavailable(String userId, String clientId, String sessionId) {
        return new SessionSummaryResult("unavailable", userId, clientId, sessionId, "", 0, List.of(), List.of(), "unavailable");
    }
}
