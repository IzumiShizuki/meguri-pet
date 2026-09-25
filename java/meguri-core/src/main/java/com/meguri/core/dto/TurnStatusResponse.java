package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TurnStatusResponse(
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("session_id") String sessionId,
        TurnStatus status,
        @JsonProperty("build_id") String buildId,
        String error) {
    @JsonCreator
    public TurnStatusResponse {
        if (turnId == null || turnId.isBlank() || sessionId == null || sessionId.isBlank()
                || buildId == null || buildId.isBlank() || status == null) {
            throw new IllegalArgumentException("turn status response fields must be present");
        }
    }

    public String getTurnId() { return turnId; }
    public String getSessionId() { return sessionId; }
    public TurnStatus getStatus() { return status; }
    public String getBuildId() { return buildId; }
    public String getError() { return error; }
}
