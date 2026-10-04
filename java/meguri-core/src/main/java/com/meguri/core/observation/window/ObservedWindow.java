package com.meguri.core.observation.window;

import java.util.Objects;

/**
 * Immutable identity and geometry snapshot for the one window Meguri is attached to.
 * The window id is intentionally represented as a string so the native adapter can
 * carry an HWND or another platform-specific identity without leaking that type into
 * the core domain.
 */
public record ObservedWindow(
        String windowId,
        long processId,
        String appName,
        String title,
        WindowBounds bounds,
        WindowAnchor anchor,
        WindowOffset offset,
        WindowVisibility visibility,
        boolean sensitive) {

    public ObservedWindow {
        windowId = requireText(windowId, "windowId");
        if (processId < 0) {
            throw new IllegalArgumentException("processId must not be negative");
        }
        appName = requireText(appName, "appName");
        title = title == null ? "" : title;
        bounds = Objects.requireNonNull(bounds, "bounds must not be null");
        anchor = Objects.requireNonNull(anchor, "anchor must not be null");
        offset = Objects.requireNonNull(offset, "offset must not be null");
        visibility = Objects.requireNonNull(visibility, "visibility must not be null");
    }

    public boolean visible() {
        return visibility.observationEligible();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
