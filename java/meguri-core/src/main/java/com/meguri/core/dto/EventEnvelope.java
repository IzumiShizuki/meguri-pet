package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class EventEnvelope {
    private final String type;
    private final String turnId;
    private final String sessionId;
    private final long sequence;
    private final Map<String, Object> data;
    private final EventMetadata metadata;

    @JsonCreator
    public EventEnvelope(@JsonProperty("type") String type,
                         @JsonProperty("turn_id") String turnId,
                         @JsonProperty("session_id") String sessionId,
                         @JsonProperty("sequence") long sequence,
                         @JsonProperty("data") Map<String, Object> data,
                         @JsonProperty("metadata") EventMetadata metadata) {
        this.type = required(type, "type");
        this.turnId = required(turnId, "turn_id");
        this.sessionId = required(sessionId, "session_id");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        this.sequence = sequence;
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.metadata = metadata == null ? new EventMetadata("trace_unknown", "meguri_local_mock") : metadata;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    @JsonProperty("type") public String getType() { return type; }
    public String type() { return type; }
    @JsonProperty("turn_id") public String getTurnId() { return turnId; }
    public String turnId() { return turnId; }
    @JsonProperty("session_id") public String getSessionId() { return sessionId; }
    public String sessionId() { return sessionId; }
    @JsonProperty("sequence") public long getSequence() { return sequence; }
    public long sequence() { return sequence; }
    @JsonProperty("data") public Map<String, Object> getData() { return data; }
    public Map<String, Object> data() { return data; }
    @JsonProperty("metadata") public EventMetadata getMetadata() { return metadata; }
    public EventMetadata metadata() { return metadata; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EventEnvelope that)) return false;
        return sequence == that.sequence && type.equals(that.type) && turnId.equals(that.turnId)
                && sessionId.equals(that.sessionId) && data.equals(that.data) && metadata.equals(that.metadata);
    }
    @Override public int hashCode() { return Objects.hash(type, turnId, sessionId, sequence, data, metadata); }
    @Override public String toString() { return "EventEnvelope[type=" + type + ", turnId=" + turnId + ", sessionId=" + sessionId + ", sequence=" + sequence + ", data=" + data + ", metadata=" + metadata + "]"; }
}
