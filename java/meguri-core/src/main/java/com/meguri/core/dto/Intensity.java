package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Intensity {
    LOW("low"), MEDIUM("medium"), HIGH("high");

    private final String value;

    Intensity(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Intensity fromValue(String value) {
        return WireValues.enumFromValue(Intensity.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
