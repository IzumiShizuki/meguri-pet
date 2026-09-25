package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class RuntimeState {
    private final String clientId;
    private final Mode mode;
    private final Relationship relationshipProfile;
    private final String outfitCode;
    private final String localTime;
    private final boolean holiday;
    private final boolean voiceEnabled;
    private final boolean screenContextEnabled;
    private final List<ExpressionTag> allowedExpressionTags;

    public RuntimeState(String clientId, Mode mode, Relationship relationshipProfile, String outfitCode,
                        String localTime, boolean holiday, boolean voiceEnabled,
                        boolean screenContextEnabled, List<ExpressionTag> allowedExpressionTags) {
        this.clientId = required(clientId, "client_id");
        this.mode = required(mode, "mode");
        this.relationshipProfile = required(relationshipProfile, "relationship_profile");
        this.outfitCode = required(outfitCode, "outfit_code");
        this.localTime = required(localTime, "local_time");
        this.holiday = holiday;
        this.voiceEnabled = voiceEnabled;
        this.screenContextEnabled = screenContextEnabled;
        this.allowedExpressionTags = allowedExpressionTags == null
                ? List.of() : List.copyOf(allowedExpressionTags);
    }

    @JsonCreator
    public RuntimeState(@JsonProperty("client_id") String clientId,
                        @JsonProperty("mode") Mode mode,
                        @JsonProperty("relationship_profile") Relationship relationshipProfile,
                        @JsonProperty("outfit_code") String outfitCode,
                        @JsonProperty("local_time") String localTime,
                        @JsonProperty("is_holiday") Boolean holiday,
                        @JsonProperty("voice_enabled") Boolean voiceEnabled,
                        @JsonProperty("screen_context_enabled") Boolean screenContextEnabled,
                        @JsonProperty("allowed_expression_tags") List<ExpressionTag> allowedExpressionTags) {
        this(clientId, mode, relationshipProfile, outfitCode, localTime,
                holiday != null && holiday, voiceEnabled != null && voiceEnabled,
                screenContextEnabled != null && screenContextEnabled, allowedExpressionTags);
    }

    private static <T> T required(T value, String field) {
        if (value == null || (value instanceof String s && s.trim().isEmpty())) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @JsonProperty("client_id") public String getClientId() { return clientId; }
    public String clientId() { return clientId; }
    @JsonProperty("mode") public Mode getMode() { return mode; }
    public Mode mode() { return mode; }
    @JsonProperty("relationship_profile") public Relationship getRelationshipProfile() { return relationshipProfile; }
    public Relationship relationshipProfile() { return relationshipProfile; }
    @JsonProperty("outfit_code") public String getOutfitCode() { return outfitCode; }
    public String outfitCode() { return outfitCode; }
    @JsonProperty("local_time") public String getLocalTime() { return localTime; }
    public String localTime() { return localTime; }
    @JsonProperty("is_holiday") public boolean isHoliday() { return holiday; }
    public boolean getHoliday() { return holiday; }
    public boolean holiday() { return holiday; }
    @JsonProperty("voice_enabled") public boolean isVoiceEnabled() { return voiceEnabled; }
    public boolean getVoiceEnabled() { return voiceEnabled; }
    @JsonProperty("screen_context_enabled") public boolean isScreenContextEnabled() { return screenContextEnabled; }
    public boolean getScreenContextEnabled() { return screenContextEnabled; }
    @JsonProperty("allowed_expression_tags") public List<ExpressionTag> getAllowedExpressionTags() { return allowedExpressionTags; }
    public List<ExpressionTag> allowedExpressionTags() { return allowedExpressionTags; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RuntimeState that)) return false;
        return holiday == that.holiday && voiceEnabled == that.voiceEnabled && screenContextEnabled == that.screenContextEnabled
                && clientId.equals(that.clientId) && mode == that.mode && relationshipProfile == that.relationshipProfile
                && outfitCode.equals(that.outfitCode) && localTime.equals(that.localTime)
                && allowedExpressionTags.equals(that.allowedExpressionTags);
    }
    @Override public int hashCode() { return Objects.hash(clientId, mode, relationshipProfile, outfitCode, localTime, holiday, voiceEnabled, screenContextEnabled, allowedExpressionTags); }
    @Override public String toString() { return "RuntimeState[clientId=" + clientId + ", mode=" + mode + ", relationshipProfile=" + relationshipProfile + ", outfitCode=" + outfitCode + ", localTime=" + localTime + ", holiday=" + holiday + ", voiceEnabled=" + voiceEnabled + ", screenContextEnabled=" + screenContextEnabled + ", allowedExpressionTags=" + allowedExpressionTags + "]"; }
}
