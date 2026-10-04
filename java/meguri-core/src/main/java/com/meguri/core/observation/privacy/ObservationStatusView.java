package com.meguri.core.observation.privacy;

import java.util.Optional;

/** Non-sensitive status projection for the desktop client. */
public record ObservationStatusView(
        ObservationStatus status,
        ObservationDecisionReason reason,
        Optional<String> windowId,
        Optional<String> applicationName) {
    public ObservationStatusView {
        if (status == null || reason == null) {
            throw new IllegalArgumentException("status and reason must not be null");
        }
        windowId = windowId == null ? Optional.empty() : windowId;
        applicationName = applicationName == null ? Optional.empty() : applicationName;
    }
}
