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
    public static final String CURRENT_PROTOCOL_VERSION = "1.0";

    private final String protocolVersion;
    private final String eventId;
    private final boolean required;
    private final String type;
    private final String turnId;
    private final String sessionId;
    private final long sequence;
    private final Map<String, Object> data;
    private final EventMetadata metadata;

    @JsonCreator
    public EventEnvelope(@JsonProperty("protocol_version") String protocolVersion,
                         @JsonProperty("event_id") String eventId,
                         @JsonProperty("required") Boolean required,
                         @JsonProperty("type") String type,
                         @JsonProperty("turn_id") String turnId,
                         @JsonProperty("session_id") String sessionId,
                         @JsonProperty("sequence") long sequence,
                         @JsonProperty("data") Map<String, Object> data,
                         @JsonProperty("metadata") EventMetadata metadata) {
        this.protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? CURRENT_PROTOCOL_VERSION : protocolVersion;
        this.eventId = eventId == null || eventId.isBlank()
                ? "event_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : eventId;
        this.required = required == null || required;
        this.type = required(type, "type");
        this.turnId = required(turnId, "turn_id");
        this.sessionId = required(sessionId, "session_id");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        this.sequence = sequence;
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.metadata = metadata == null ? new EventMetadata("trace_unknown", "meguri_local_mock") : metadata;
    }

    public EventEnvelope(String protocolVersion, String eventId, boolean required,
                         String type, String turnId, String sessionId, long sequence,
                         Map<String, Object> data, EventMetadata metadata) {
        this(protocolVersion, eventId, Boolean.valueOf(required), type, turnId, sessionId, sequence, data, metadata);
    }

    /** Compatibility constructor for local fixtures while protocol v1 rolls out. */
    public EventEnvelope(String type, String turnId, String sessionId, long sequence,
                         Map<String, Object> data, EventMetadata metadata) {
        this(CURRENT_PROTOCOL_VERSION, null, true, type, turnId, sessionId, sequence, data, metadata);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    @JsonProperty("protocol_version") public String getProtocolVersion() { return protocolVersion; }
    public String protocolVersion() { return protocolVersion; }
    @JsonProperty("event_id") public String getEventId() { return eventId; }
    public String eventId() { return eventId; }
    @JsonProperty("required") public boolean isRequired() { return required; }
    public boolean required() { return required; }
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
        return sequence == that.sequence && required == that.required
                && protocolVersion.equals(that.protocolVersion) && eventId.equals(that.eventId)
                && type.equals(that.type) && turnId.equals(that.turnId)
                && sessionId.equals(that.sessionId) && data.equals(that.data) && metadata.equals(that.metadata);
    }
    @Override public int hashCode() { return Objects.hash(protocolVersion, eventId, required, type, turnId, sessionId, sequence, data, metadata); }
    @Override public String toString() { return "EventEnvelope[protocolVersion=" + protocolVersion + ", eventId=" + eventId + ", required=" + required + ", type=" + type + ", turnId=" + turnId + ", sessionId=" + sessionId + ", sequence=" + sequence + ", data=" + data + ", metadata=" + metadata + "]"; }
}
