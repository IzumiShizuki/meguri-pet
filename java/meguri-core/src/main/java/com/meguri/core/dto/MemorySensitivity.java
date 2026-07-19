package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemorySensitivity {
    NORMAL("normal"), PRIVATE("private"), SENSITIVE("sensitive");

    private final String value;
    MemorySensitivity(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
    @JsonCreator
    public static MemorySensitivity fromValue(String value) { return WireValues.enumFromValue(MemorySensitivity.class, value); }
    @Override public String toString() { return value; }
}
