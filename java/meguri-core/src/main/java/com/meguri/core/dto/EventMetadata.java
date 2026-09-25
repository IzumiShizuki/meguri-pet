package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class EventMetadata {
    private final String traceId;
    private final String source;
    private final Instant createdAt;
    private final String buildId;

    @JsonCreator
    public EventMetadata(@JsonProperty("trace_id") String traceId,
                         @JsonProperty("source") String source,
                         @JsonProperty("created_at") Instant createdAt,
                         @JsonProperty("build_id") String buildId) {
        this.traceId = required(traceId, "trace_id");
        this.source = source == null || source.isBlank() ? "meguri-core" : source;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.buildId = required(buildId, "build_id");
    }

    public EventMetadata(String traceId, String buildId) {
        this(traceId, "meguri-core", Instant.now(), buildId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    @JsonProperty("trace_id") public String getTraceId() { return traceId; }
    public String traceId() { return traceId; }
    @JsonProperty("source") public String getSource() { return source; }
    public String source() { return source; }
    @JsonProperty("created_at") public Instant getCreatedAt() { return createdAt; }
    public Instant createdAt() { return createdAt; }
    @JsonProperty("build_id") public String getBuildId() { return buildId; }
    public String buildId() { return buildId; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EventMetadata that)) return false;
        return traceId.equals(that.traceId) && source.equals(that.source)
                && createdAt.equals(that.createdAt) && buildId.equals(that.buildId);
    }
    @Override public int hashCode() { return Objects.hash(traceId, source, createdAt, buildId); }
    @Override public String toString() { return "EventMetadata[traceId=" + traceId + ", source=" + source + ", createdAt=" + createdAt + ", buildId=" + buildId + "]"; }
}
