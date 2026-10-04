package com.meguri.core.observation.provider;

import com.meguri.core.observation.screen.ScreenContext;

import java.util.Objects;
import java.util.Optional;

public record ObservationProviderResult(
        Status status,
        Optional<ScreenContext> context,
        String reason) {

    public enum Status {
        AVAILABLE,
        UNAVAILABLE
    }

    public ObservationProviderResult {
        status = Objects.requireNonNull(status, "status must not be null");
        context = context == null ? Optional.empty() : context;
        reason = reason == null ? "" : reason.trim();
        if (status == Status.AVAILABLE && context.isEmpty()) {
            throw new IllegalArgumentException("available provider result must contain context");
        }
        if (status == Status.UNAVAILABLE && context.isPresent()) {
            throw new IllegalArgumentException("unavailable provider result must not contain context");
        }
        if (status == Status.UNAVAILABLE && reason.isBlank()) {
            throw new IllegalArgumentException("unavailable provider result must contain a reason");
        }
    }

    public static ObservationProviderResult available(ScreenContext context) {
        return new ObservationProviderResult(Status.AVAILABLE, Optional.of(context), "");
    }

    public static ObservationProviderResult unavailable(String reason) {
        return new ObservationProviderResult(Status.UNAVAILABLE, Optional.empty(), reason);
    }
}
