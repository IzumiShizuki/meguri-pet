package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TurnStatus {
    ACCEPTED("accepted"), RUNNING("running"), COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled");

    private final String value;

    TurnStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TurnStatus fromValue(String value) {
        return WireValues.enumFromValue(TurnStatus.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
