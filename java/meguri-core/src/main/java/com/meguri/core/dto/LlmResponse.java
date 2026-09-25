package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Strict structured output returned by an LLM. */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class LlmResponse {
    private final String reply;
    private final ExpressionTag expressionTag;
    private final Intensity expressionIntensity;
    private final VoiceStyle voiceStyle;
    private final List<MemoryCandidate> memoryCandidates;

    @JsonCreator
    public LlmResponse(@JsonProperty("reply") String reply,
                       @JsonProperty("expression_tag") ExpressionTag expressionTag,
                       @JsonProperty("expression_intensity") Intensity expressionIntensity,
                       @JsonProperty("voice_style") VoiceStyle voiceStyle,
                       @JsonProperty("memory_candidates") List<MemoryCandidate> memoryCandidates) {
        if (reply == null || reply.trim().isEmpty()) throw new IllegalArgumentException("reply must not be blank");
        this.reply = reply;
        this.expressionTag = Objects.requireNonNull(expressionTag, "expression_tag must be present");
        this.expressionIntensity = Objects.requireNonNull(expressionIntensity, "expression_intensity must be present");
        this.voiceStyle = Objects.requireNonNull(voiceStyle, "voice_style must be present");
        List<MemoryCandidate> candidates = Objects.requireNonNull(memoryCandidates, "memory_candidates must be present");
        candidates = List.copyOf(candidates);
        if (candidates.size() > 3) throw new IllegalArgumentException("memory_candidates must contain at most 3 items");
        this.memoryCandidates = Collections.unmodifiableList(candidates);
    }

    public LlmResponse(String reply) {
        this(reply, ExpressionTag.NEUTRAL, Intensity.LOW, VoiceStyle.NEUTRAL, List.of());
    }

    @JsonProperty("reply") public String getReply() { return reply; }
    public String reply() { return reply; }
    @JsonProperty("expression_tag") public ExpressionTag getExpressionTag() { return expressionTag; }
    public ExpressionTag expressionTag() { return expressionTag; }
    @JsonProperty("expression_intensity") public Intensity getExpressionIntensity() { return expressionIntensity; }
    public Intensity expressionIntensity() { return expressionIntensity; }
    @JsonProperty("voice_style") public VoiceStyle getVoiceStyle() { return voiceStyle; }
    public VoiceStyle voiceStyle() { return voiceStyle; }
    @JsonProperty("memory_candidates") public List<MemoryCandidate> getMemoryCandidates() { return memoryCandidates; }
    public List<MemoryCandidate> memoryCandidates() { return memoryCandidates; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LlmResponse that)) return false;
        return reply.equals(that.reply) && expressionTag == that.expressionTag
                && expressionIntensity == that.expressionIntensity && voiceStyle == that.voiceStyle
                && memoryCandidates.equals(that.memoryCandidates);
    }
    @Override public int hashCode() { return Objects.hash(reply, expressionTag, expressionIntensity, voiceStyle, memoryCandidates); }
    @Override public String toString() { return "LlmResponse[reply=" + reply + ", expressionTag=" + expressionTag + ", expressionIntensity=" + expressionIntensity + ", voiceStyle=" + voiceStyle + ", memoryCandidates=" + memoryCandidates + "]"; }
}
