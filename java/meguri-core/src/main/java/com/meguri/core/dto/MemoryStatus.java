package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryStatus {
    WRITTEN("written"), PENDING("pending"), UNAVAILABLE("unavailable");
    private final String value;
    MemoryStatus(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
    @JsonCreator public static MemoryStatus fromValue(String value) { return WireValues.enumFromValue(MemoryStatus.class, value); }
    @Override public String toString() { return value; }
}
