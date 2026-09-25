package com.meguri.core.persona.presentation;

public record PresentationIntent(String expressionTag, String voiceStyle, String gestureTag, double intensity) {
    public PresentationIntent {
        expressionTag = clean(expressionTag, "neutral"); voiceStyle = clean(voiceStyle, "neutral"); gestureTag = clean(gestureTag, "none");
        if (intensity < 0 || intensity > 1) throw new IllegalArgumentException("intensity must be in [0,1]");
    }
    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        if (!value.matches("[a-z0-9_]{1,40}")) throw new IllegalArgumentException("presentation tags must be semantic identifiers");
        return value;
    }
}
