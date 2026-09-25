package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.adapter.domain.ReplayPolicy;

import java.time.Instant;
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
    private final String requiredExtension;
    private final String type;
    private final String turnId;
    private final String sessionId;
    private final long sequence;
    private final ReplayPolicy replayPolicy;
    private final Instant createdAt;
    private final Map<String, Object> data;
    private final EventMetadata metadata;

    @JsonCreator
    public EventEnvelope(@JsonProperty("protocol_version") String protocolVersion,
                         @JsonProperty("event_id") String eventId,
                         @JsonProperty("required") Boolean required,
                         @JsonProperty("required_extension") String requiredExtension,
                         @JsonProperty("type") String type,
                         @JsonProperty("turn_id") String turnId,
                         @JsonProperty("session_id") String sessionId,
                         @JsonProperty("sequence") long sequence,
                         @JsonProperty("replay_policy") ReplayPolicy replayPolicy,
                         @JsonProperty("created_at") Instant createdAt,
                         @JsonProperty("data") Map<String, Object> data,
                         @JsonProperty("metadata") EventMetadata metadata) {
        this.protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? CURRENT_PROTOCOL_VERSION : protocolVersion;
        this.eventId = eventId == null || eventId.isBlank()
                ? "event_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : eventId;
        this.requiredExtension = optional(requiredExtension);
        this.required = this.requiredExtension != null || required == null || required;
        this.type = required(type, "type");
        this.turnId = required(turnId, "turn_id");
        this.sessionId = required(sessionId, "session_id");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        this.sequence = sequence;
        this.replayPolicy = replayPolicy == null ? ReplayPolicy.ALWAYS : replayPolicy;
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.metadata = metadata == null ? new EventMetadata("trace_unknown", "meguri_local_mock") : metadata;
        this.createdAt = createdAt == null ? this.metadata.getCreatedAt() : createdAt;
    }

    public EventEnvelope(String protocolVersion, String eventId, boolean required,
                         String requiredExtension, String type, String turnId,
                         String sessionId, long sequence, ReplayPolicy replayPolicy,
                         Instant createdAt, Map<String, Object> data, EventMetadata metadata) {
        this(protocolVersion, eventId, Boolean.valueOf(required), requiredExtension, type,
                turnId, sessionId, sequence, replayPolicy, createdAt, data, metadata);
    }

    public EventEnvelope(String protocolVersion, String eventId, boolean required,
                         String type, String turnId, String sessionId, long sequence,
                         Map<String, Object> data, EventMetadata metadata) {
        this(protocolVersion, eventId, Boolean.valueOf(required), null, type, turnId,
                sessionId, sequence, ReplayPolicy.ALWAYS, null, data, metadata);
    }

    /** Compatibility constructor for local fixtures while protocol v1 rolls out. */
    public EventEnvelope(String type, String turnId, String sessionId, long sequence,
                         Map<String, Object> data, EventMetadata metadata) {
        this(CURRENT_PROTOCOL_VERSION, null, true, null, type, turnId, sessionId,
                sequence, ReplayPolicy.ALWAYS, null, data, metadata);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @JsonProperty("protocol_version") public String getProtocolVersion() { return protocolVersion; }
    public String protocolVersion() { return protocolVersion; }
    @JsonProperty("event_id") public String getEventId() { return eventId; }
    public String eventId() { return eventId; }
    @JsonProperty("required") public boolean isRequired() { return required; }
    public boolean required() { return required; }
    @JsonProperty("required_extension") public String getRequiredExtension() { return requiredExtension; }
    public String requiredExtension() { return requiredExtension; }
    @JsonProperty("type") public String getType() { return type; }
    public String type() { return type; }
    @JsonProperty("turn_id") public String getTurnId() { return turnId; }
    public String turnId() { return turnId; }
    @JsonProperty("session_id") public String getSessionId() { return sessionId; }
    public String sessionId() { return sessionId; }
    @JsonProperty("sequence") public long getSequence() { return sequence; }
    public long sequence() { return sequence; }
    @JsonProperty("replay_policy") public ReplayPolicy getReplayPolicy() { return replayPolicy; }
    public ReplayPolicy replayPolicy() { return replayPolicy; }
    @JsonProperty("created_at") public Instant getCreatedAt() { return createdAt; }
    public Instant createdAt() { return createdAt; }
    @JsonProperty("data") public Map<String, Object> getData() { return data; }
    public Map<String, Object> data() { return data; }
    @JsonProperty("metadata") public EventMetadata getMetadata() { return metadata; }
    public EventMetadata metadata() { return metadata; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EventEnvelope that)) return false;
        return sequence == that.sequence && required == that.required
                && protocolVersion.equals(that.protocolVersion) && eventId.equals(that.eventId)
                && Objects.equals(requiredExtension, that.requiredExtension)
                && type.equals(that.type) && turnId.equals(that.turnId)
                && sessionId.equals(that.sessionId) && replayPolicy == that.replayPolicy
                && createdAt.equals(that.createdAt) && data.equals(that.data) && metadata.equals(that.metadata);
    }
    @Override public int hashCode() { return Objects.hash(protocolVersion, eventId, required, requiredExtension, type, turnId, sessionId, sequence, replayPolicy, createdAt, data, metadata); }
    @Override public String toString() { return "EventEnvelope[protocolVersion=" + protocolVersion + ", eventId=" + eventId + ", required=" + required + ", requiredExtension=" + requiredExtension + ", type=" + type + ", turnId=" + turnId + ", sessionId=" + sessionId + ", sequence=" + sequence + ", replayPolicy=" + replayPolicy + ", createdAt=" + createdAt + ", data=" + data + ", metadata=" + metadata + "]"; }
}
