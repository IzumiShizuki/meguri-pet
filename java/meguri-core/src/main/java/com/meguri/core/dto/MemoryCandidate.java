package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class MemoryCandidate {
    private final MemoryType type;
    private final String summary;
    private final double confidence;
    private final MemorySensitivity sensitivity;
    private final MemorySourceScope sourceScope;

    public MemoryCandidate(MemoryType type, String summary, double confidence,
                           MemorySensitivity sensitivity, MemorySourceScope sourceScope) {
        this.type = required(type, "type");
        this.summary = required(summary, "summary");
        if (this.summary.length() > 500) throw new IllegalArgumentException("summary must be at most 500 characters");
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        this.confidence = confidence;
        this.sensitivity = Objects.requireNonNull(sensitivity, "sensitivity must be present");
        this.sourceScope = Objects.requireNonNull(sourceScope, "source_scope must be present");
    }

    public MemoryCandidate(MemoryType type, String summary, double confidence) {
        this(type, summary, confidence, MemorySensitivity.NORMAL, MemorySourceScope.CURRENT_MESSAGE);
    }

    @JsonCreator
    public MemoryCandidate(@JsonProperty("type") MemoryType type,
                           @JsonProperty("summary") String summary,
                           @JsonProperty("confidence") Double confidence,
                           @JsonProperty("sensitivity") MemorySensitivity sensitivity,
                           @JsonProperty("source_scope") MemorySourceScope sourceScope) {
        this(type, summary, requiredConfidence(confidence),
                sensitivity == null ? MemorySensitivity.NORMAL : sensitivity,
                sourceScope == null ? MemorySourceScope.CURRENT_MESSAGE : sourceScope);
    }

    private static double requiredConfidence(Double value) {
        if (value == null) throw new IllegalArgumentException("confidence must be present");
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null || (value instanceof String s && s.trim().isEmpty())) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @JsonProperty("type") public MemoryType getType() { return type; }
    public MemoryType type() { return type; }
    @JsonProperty("summary") public String getSummary() { return summary; }
    public String summary() { return summary; }
    @JsonProperty("confidence") public double getConfidence() { return confidence; }
    public double confidence() { return confidence; }
    @JsonProperty("sensitivity") public MemorySensitivity getSensitivity() { return sensitivity; }
    public MemorySensitivity sensitivity() { return sensitivity; }
    @JsonProperty("source_scope") public MemorySourceScope getSourceScope() { return sourceScope; }
    public MemorySourceScope sourceScope() { return sourceScope; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MemoryCandidate that)) return false;
        return Double.compare(confidence, that.confidence) == 0 && type == that.type
                && summary.equals(that.summary) && sensitivity == that.sensitivity && sourceScope == that.sourceScope;
    }
    @Override public int hashCode() { return Objects.hash(type, summary, confidence, sensitivity, sourceScope); }
    @Override public String toString() { return "MemoryCandidate[type=" + type + ", summary=" + summary + ", confidence=" + confidence + ", sensitivity=" + sensitivity + ", sourceScope=" + sourceScope + "]"; }
}
