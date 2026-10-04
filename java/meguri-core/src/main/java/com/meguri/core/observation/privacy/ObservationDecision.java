package com.meguri.core.observation.privacy;

import java.util.Objects;

public record ObservationDecision(boolean allowed, ObservationDecisionReason reason) {
    public ObservationDecision {
        reason = Objects.requireNonNull(reason, "reason must not be null");
        if (allowed != (reason == ObservationDecisionReason.ALLOWED)) {
            throw new IllegalArgumentException("allowed must match the decision reason");
        }
    }

    public static ObservationDecision allow() {
        return new ObservationDecision(true, ObservationDecisionReason.ALLOWED);
    }

    public static ObservationDecision deny(ObservationDecisionReason reason) {
        if (reason == ObservationDecisionReason.ALLOWED) {
            throw new IllegalArgumentException("allowed is not a denial reason");
        }
        return new ObservationDecision(false, reason);
    }
}
