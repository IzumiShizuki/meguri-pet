package com.meguri.core.observability;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** Content-free reasons for a milestone that cannot truthfully be observed. */
public enum TurnLatencyMissingReason {
    NOT_RECORDED("NOT_RECORDED"),
    NOT_APPLICABLE("NOT_APPLICABLE"),
    STAGE_BYPASSED("STAGE_BYPASSED"),
    PROVIDER_UNSUPPORTED("PROVIDER_UNSUPPORTED"),
    CLIENT_UNSUPPORTED("CLIENT_UNSUPPORTED"),
    REQUEST_FAILED("REQUEST_FAILED"),
    CANCELLED("CANCELLED"),
    DEADLINE_EXCEEDED("DEADLINE_EXCEEDED"),
    CLOCK_REGRESSION("CLOCK_REGRESSION");

    private final String wireName;

    TurnLatencyMissingReason(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static TurnLatencyMissingReason fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing reason is required");
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(reason -> reason.wireName.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown Turn latency missing reason: " + value));
    }
}
