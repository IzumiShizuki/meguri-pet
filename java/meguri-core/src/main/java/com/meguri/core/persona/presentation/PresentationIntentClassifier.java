package com.meguri.core.persona.presentation;

import java.util.Map;

/** Validates a model-produced semantic object without accepting resource identifiers. */
public final class PresentationIntentClassifier {
    public PresentationIntent classify(Map<String, ?> value) {
        if (value == null) return new PresentationIntent("neutral", "neutral", "none", 0);
        Object raw = value.get("intensity"); double intensity = raw instanceof Number n ? n.doubleValue() : 0;
        return new PresentationIntent(text(value, "expression_tag", "neutral"), text(value, "voice_style", "neutral"),
                text(value, "gesture_tag", "none"), intensity);
    }
    private static String text(Map<String, ?> value, String key, String fallback) {
        Object raw = value.get(key); return raw instanceof String text ? text : fallback;
    }
}
