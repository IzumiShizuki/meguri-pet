package com.meguri.core.observation.screen;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compressed semantic observation. Raw screenshots and OCR payloads are deliberately
 * not part of this model.
 */
public record ScreenContext(
        String scopeId,
        String application,
        String activity,
        String content,
        Set<String> signals,
        ScreenObservationSource source,
        double confidence,
        Instant observedAt,
        Instant expiresAt) {

    public ScreenContext {
        scopeId = requireText(scopeId, "scopeId");
        application = requireText(application, "application");
        activity = activity == null ? "" : activity.trim();
        content = content == null ? "" : content.trim();
        signals = normalizeSignals(signals);
        source = Objects.requireNonNull(source, "source must not be null");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must be after observedAt");
        }
    }

    public boolean isCurrentAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !now.isBefore(observedAt) && now.isBefore(expiresAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Set<String> normalizeSignals(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }
}
