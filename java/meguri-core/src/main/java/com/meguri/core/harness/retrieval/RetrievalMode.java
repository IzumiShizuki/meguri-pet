package com.meguri.core.harness.retrieval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Server-enforced retrieval depth for one turn. */
public enum RetrievalMode {
    NONE,
    FAST,
    SLOW;

    /** Missing mode preserves the pre-mode behavior: all lanes, with Web policy gating. */
    public static RetrievalMode compatibleDefault() {
        return SLOW;
    }

    @JsonCreator
    public static RetrievalMode fromWireValue(String value) {
        if (value == null || value.isBlank()) return compatibleDefault();
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported retrieval_mode: " + value, error);
        }
    }

    @JsonValue
    public String wireValue() {
        return name();
    }

    public boolean permitsLocalRetrieval() {
        return this != NONE;
    }

    public boolean permitsOpenRetrieval() {
        return this == SLOW;
    }
}
