package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Mode {
    WORK("work"), PRIVATE("private"), SLEEP("sleep"), EVENT("event");

    private final String value;

    Mode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Mode fromValue(String value) {
        return WireValues.enumFromValue(Mode.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
