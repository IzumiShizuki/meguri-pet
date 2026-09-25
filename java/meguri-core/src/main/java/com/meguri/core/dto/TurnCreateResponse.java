package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TurnCreateResponse(
        @JsonProperty("protocol_version") String protocolVersion,
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("build_id") String buildId,
        TurnStatus status,
        @JsonProperty("event_cursor") long eventCursor,
        @JsonProperty("events_url") String eventsUrl) {
    public TurnCreateResponse(
            String turnId, String sessionId, String buildId, TurnStatus status) {
        this(EventEnvelope.CURRENT_PROTOCOL_VERSION, turnId, sessionId, buildId,
                status, 0L, "/v1/sessions/" + sessionId + "/events");
    }

    @JsonCreator
    public TurnCreateResponse {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? EventEnvelope.CURRENT_PROTOCOL_VERSION : protocolVersion;
        if (turnId == null || turnId.isBlank() || sessionId == null || sessionId.isBlank()
                || buildId == null || buildId.isBlank() || status == null
                || eventCursor < 0 || eventsUrl == null || eventsUrl.isBlank()) {
            throw new IllegalArgumentException("turn create response fields must be present");
        }
    }

    public String getProtocolVersion() { return protocolVersion; }
    public String getTurnId() { return turnId; }
    public String getSessionId() { return sessionId; }
    public String getBuildId() { return buildId; }
    public TurnStatus getStatus() { return status; }
    public long getEventCursor() { return eventCursor; }
    public String getEventsUrl() { return eventsUrl; }
}
