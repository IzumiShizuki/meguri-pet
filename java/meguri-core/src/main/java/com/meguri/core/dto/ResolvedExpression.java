package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class ResolvedExpression {
    private final ExpressionTag expressionTag;
    private final Intensity expressionIntensity;
    private final String outfitCode;
    private final String expressionCode;
    private final String spriteFile;
    private final String motionTag;

    @JsonCreator
    public ResolvedExpression(@JsonProperty("expression_tag") ExpressionTag expressionTag,
                              @JsonProperty("expression_intensity") Intensity expressionIntensity,
                              @JsonProperty("outfit_code") String outfitCode,
                              @JsonProperty("expression_code") String expressionCode,
                              @JsonProperty("sprite_file") String spriteFile,
                              @JsonProperty("motion_tag") String motionTag) {
        this.expressionTag = expressionTag == null ? ExpressionTag.NEUTRAL : expressionTag;
        this.expressionIntensity = expressionIntensity == null ? Intensity.LOW : expressionIntensity;
        if (outfitCode == null || outfitCode.isBlank()) throw new IllegalArgumentException("outfit_code must not be blank");
        this.outfitCode = outfitCode;
        this.expressionCode = expressionCode;
        this.spriteFile = spriteFile;
        this.motionTag = motionTag == null || motionTag.isBlank() ? motionFor(this.expressionTag) : motionTag;
    }

    public ResolvedExpression(ExpressionTag expressionTag, Intensity expressionIntensity, String outfitCode) {
        this(expressionTag, expressionIntensity, outfitCode, null, null, null);
    }

    public ResolvedExpression(ExpressionTag expressionTag, Intensity expressionIntensity, String outfitCode,
                              String expressionCode, String spriteFile) {
        this(expressionTag, expressionIntensity, outfitCode, expressionCode, spriteFile, null);
    }

    @JsonProperty("expression_tag") public ExpressionTag getExpressionTag() { return expressionTag; }
    public ExpressionTag expressionTag() { return expressionTag; }
    @JsonProperty("expression_intensity") public Intensity getExpressionIntensity() { return expressionIntensity; }
    public Intensity expressionIntensity() { return expressionIntensity; }
    @JsonProperty("outfit_code") public String getOutfitCode() { return outfitCode; }
    public String outfitCode() { return outfitCode; }
    @JsonProperty("expression_code") public String getExpressionCode() { return expressionCode; }
    public String expressionCode() { return expressionCode; }
    @JsonProperty("sprite_file") public String getSpriteFile() { return spriteFile; }
    public String spriteFile() { return spriteFile; }
    @JsonProperty("motion_tag") public String getMotionTag() { return motionTag; }
    public String motionTag() { return motionTag; }

    private static String motionFor(ExpressionTag tag) {
        return switch (tag) {
            case HAPPY, AFFECTIONATE, EXCITED -> "Happy";
            case SAD -> "Sad";
            case ANGRY -> "Angry";
            case SURPRISED -> "Surprise";
            case EMBARRASSED -> "Awkward";
            case CONFUSED, WORRIED -> "Think";
            case TEASING -> "Curious";
            case SLEEPY, NEUTRAL -> "Idle";
        };
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ResolvedExpression that)) return false;
        return expressionTag == that.expressionTag && expressionIntensity == that.expressionIntensity
                && outfitCode.equals(that.outfitCode) && Objects.equals(expressionCode, that.expressionCode)
                && Objects.equals(spriteFile, that.spriteFile) && Objects.equals(motionTag, that.motionTag);
    }
    @Override public int hashCode() { return Objects.hash(expressionTag, expressionIntensity, outfitCode, expressionCode, spriteFile, motionTag); }
    @Override public String toString() { return "ResolvedExpression[expressionTag=" + expressionTag + ", expressionIntensity=" + expressionIntensity + ", outfitCode=" + outfitCode + ", expressionCode=" + expressionCode + ", spriteFile=" + spriteFile + ", motionTag=" + motionTag + "]"; }
}
