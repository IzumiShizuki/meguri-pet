package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryType {
    PREFERENCE("preference"), IDENTITY("identity"), PROJECT("project"), COMMITMENT("commitment"),
    RELATIONSHIP("relationship"), ROUTINE("routine"), EVENT("event");

    private final String value;

    MemoryType(String value) { this.value = value; }

    @JsonValue public String value() { return value; }

    @JsonCreator
    public static MemoryType fromValue(String value) { return WireValues.enumFromValue(MemoryType.class, value); }

    @Override public String toString() { return value; }
}
