package com.meguri.core.observation.window;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Current attachment for one user and one desktop client instance. */
public record WindowAttachmentState(
        String userId,
        String clientId,
        Optional<ObservedWindow> window,
        WindowAttachmentStatus status,
        Instant changedAt) {

    public WindowAttachmentState {
        userId = requireText(userId, "userId");
        clientId = requireText(clientId, "clientId");
        window = window == null ? Optional.empty() : window;
        status = Objects.requireNonNull(status, "status must not be null");
        changedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (status == WindowAttachmentStatus.DETACHED && window.isPresent()) {
            throw new IllegalArgumentException("detached state must not contain a window");
        }
        if (status != WindowAttachmentStatus.DETACHED && window.isEmpty()) {
            throw new IllegalArgumentException("attached or unavailable state must contain a window");
        }
    }

    public static WindowAttachmentState detached(String userId, String clientId, Instant now) {
        return new WindowAttachmentState(
                userId,
                clientId,
                Optional.empty(),
                WindowAttachmentStatus.DETACHED,
                now);
    }

    public boolean hasAttachment() {
        return window.isPresent();
    }

    public boolean observationEligible() {
        return status == WindowAttachmentStatus.ATTACHED && window.map(ObservedWindow::visible).orElse(false);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
