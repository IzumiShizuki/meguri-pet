package com.meguri.core.observation.window;

import java.time.Instant;
import java.util.Objects;

/** Pure state transitions for explicit user-controlled window attachment. */
public final class WindowAttachmentReducer {
    private WindowAttachmentReducer() {
    }

    public static WindowAttachmentState attach(
            WindowAttachmentState current,
            ObservedWindow window,
            Instant now) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return stateFor(current, window, now);
    }

    public static WindowAttachmentState update(
            WindowAttachmentState current,
            ObservedWindow updatedWindow,
            Instant now) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(updatedWindow, "updatedWindow must not be null");
        Objects.requireNonNull(now, "now must not be null");
        ObservedWindow attachedWindow = current.window()
                .orElseThrow(() -> new IllegalStateException("cannot update a detached window"));
        if (!attachedWindow.windowId().equals(updatedWindow.windowId())) {
            throw new IllegalArgumentException("window update must use the currently attached window identity");
        }
        return stateFor(current, updatedWindow, now);
    }

    public static WindowAttachmentState detach(WindowAttachmentState current, Instant now) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return WindowAttachmentState.detached(current.userId(), current.clientId(), now);
    }

    private static WindowAttachmentState stateFor(
            WindowAttachmentState current,
            ObservedWindow window,
            Instant now) {
        WindowAttachmentStatus status = window.visible()
                ? WindowAttachmentStatus.ATTACHED
                : WindowAttachmentStatus.UNAVAILABLE;
        return new WindowAttachmentState(
                current.userId(),
                current.clientId(),
                java.util.Optional.of(window),
                status,
                now);
    }
}
