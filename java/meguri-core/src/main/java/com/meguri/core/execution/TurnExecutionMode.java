package com.meguri.core.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a Turn is executed. This is deliberately independent from retrieval mode.
 */
public enum TurnExecutionMode {
    FAST,
    THINK,
    AGENT;

    @JsonCreator
    public static TurnExecutionMode fromWireValue(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalArgumentException("unsupported execution_mode: " + value, unsupported);
        }
    }

    @JsonValue
    public String wireValue() {
        return name();
    }
}
