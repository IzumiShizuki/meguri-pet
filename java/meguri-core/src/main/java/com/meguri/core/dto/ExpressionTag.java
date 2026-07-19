package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Expressions accepted by the Meguri response contract. */
public enum ExpressionTag {
    AFFECTIONATE("affectionate"),
    ANGRY("angry"),
    CONFUSED("confused"),
    EMBARRASSED("embarrassed"),
    EXCITED("excited"),
    HAPPY("happy"),
    NEUTRAL("neutral"),
    SAD("sad"),
    SLEEPY("sleepy"),
    SURPRISED("surprised"),
    TEASING("teasing"),
    WORRIED("worried");

    private final String value;

    ExpressionTag(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ExpressionTag fromValue(String value) {
        return WireValues.enumFromValue(ExpressionTag.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
