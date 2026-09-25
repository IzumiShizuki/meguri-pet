package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemorySourceScope {
    CURRENT_MESSAGE("current_message"), CONVERSATION("conversation");

    private final String value;
    MemorySourceScope(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
    @JsonCreator
    public static MemorySourceScope fromValue(String value) { return WireValues.enumFromValue(MemorySourceScope.class, value); }
    @Override public String toString() { return value; }
}
