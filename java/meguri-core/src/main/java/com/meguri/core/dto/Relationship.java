package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Relationship {
    SIBLING("sibling"), PURSUIT("pursuit"), LOVER("lover");

    private final String value;

    Relationship(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Relationship fromValue(String value) {
        return WireValues.enumFromValue(Relationship.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
