package com.meguri.core.observation.provider;

import com.meguri.core.observation.window.ObservedWindow;

import java.time.Instant;
import java.util.Objects;

/** Provider request containing window metadata only; it carries no raw capture payload. */
public record ObservationRequest(String scopeId, ObservedWindow window, Instant requestedAt) {
    public ObservationRequest {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        window = Objects.requireNonNull(window, "window must not be null");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }
}
