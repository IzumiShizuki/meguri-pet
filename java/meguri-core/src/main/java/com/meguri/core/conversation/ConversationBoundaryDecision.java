package com.meguri.core.conversation;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Result of accepting a provisional desktop segment or merging it into its parent. */
public record ConversationBoundaryDecision(
        @JsonProperty("new_session") boolean newSession,
        double confidence,
        String reason,
        @JsonProperty("active_session_id") String activeSessionId) { }
