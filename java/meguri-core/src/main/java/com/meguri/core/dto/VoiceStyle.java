package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum VoiceStyle {
    NEUTRAL("neutral"), SOFT("soft"), CHEERFUL("cheerful"), RESTRAINED("restrained"),
    SLEEPY("sleepy"), TEASING("teasing"), AFFECTIONATE("affectionate"), WORRIED("worried");

    private final String value;

    VoiceStyle(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static VoiceStyle fromValue(String value) {
        return WireValues.enumFromValue(VoiceStyle.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
