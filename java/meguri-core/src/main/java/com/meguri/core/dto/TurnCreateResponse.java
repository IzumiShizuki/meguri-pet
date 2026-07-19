package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TurnCreateResponse(
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("build_id") String buildId,
        TurnStatus status) {
    @JsonCreator
    public TurnCreateResponse {
        if (turnId == null || turnId.isBlank() || sessionId == null || sessionId.isBlank()
                || buildId == null || buildId.isBlank() || status == null) {
            throw new IllegalArgumentException("turn create response fields must be present");
        }
    }

    public String getTurnId() { return turnId; }
    public String getSessionId() { return sessionId; }
    public String getBuildId() { return buildId; }
    public TurnStatus getStatus() { return status; }
}
