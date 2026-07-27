package com.meguri.core.conversation;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The desktop supplies IDs only; message histories are read server-side by identity. */
public record ConversationBoundaryRequest(
        @JsonProperty("user_id") String userId,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("parent_session_id") String parentSessionId,
        @JsonProperty("candidate_session_id") String candidateSessionId,
        @JsonProperty("minimum_confidence") double minimumConfidence) { }
