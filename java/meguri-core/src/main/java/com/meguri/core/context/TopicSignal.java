package com.meguri.core.context;

import java.util.Locale;

/** Immutable topic evidence kept separate from retrieval query rewriting. */
public record TopicSignal(
        String label,
        double confidence,
        String boundaryReason,
        String detectorRevision) {
    public TopicSignal {
        label = normalize(label, "general");
        if (confidence < 0d || confidence > 1d || Double.isNaN(confidence)) {
            throw new IllegalArgumentException("topic confidence must be between 0 and 1");
        }
        boundaryReason = normalize(boundaryReason, "unspecified");
        detectorRevision = normalize(detectorRevision, "topic-detector-v1-deterministic");
    }

    public static TopicSignal fromLegacy(String topicHint, double topicConfidence) {
        return new TopicSignal(topicHint, Math.max(0d, Math.min(1d, topicConfidence)),
                "legacy topic hint", "topic-detector-legacy");
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
