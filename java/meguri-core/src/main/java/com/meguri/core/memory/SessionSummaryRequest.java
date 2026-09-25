package com.meguri.core.memory;

import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.runtime.SessionContextStore;

import java.util.List;

/** A bounded session snapshot submitted to the Python authoritative memory service. */
public record SessionSummaryRequest(
        String userId,
        String clientId,
        String sessionId,
        List<SessionContextStore.Message> messages,
        List<MemoryCandidate> structuredCandidates,
        boolean writeCandidates) {
    public SessionSummaryRequest(String userId, String clientId, String sessionId,
                                 List<SessionContextStore.Message> messages) {
        this(userId, clientId, sessionId, messages, List.of(), false);
    }

    public SessionSummaryRequest {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId must not be blank");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
        messages = messages == null ? List.of() : List.copyOf(messages);
        structuredCandidates = structuredCandidates == null ? List.of() : List.copyOf(structuredCandidates);
        if (structuredCandidates.size() > 3) throw new IllegalArgumentException("at most 3 structured candidates are allowed");
        if (messages.size() < 2 || messages.size() > 20) {
            throw new IllegalArgumentException("session summary requires 2 to 20 messages");
        }
    }
}
