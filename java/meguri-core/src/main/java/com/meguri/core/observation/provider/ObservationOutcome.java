package com.meguri.core.observation.provider;

import com.meguri.core.observation.privacy.ObservationDecision;
import com.meguri.core.observation.screen.ScreenContext;
import com.meguri.core.observation.screen.SemanticDiff;

import java.util.Objects;
import java.util.Optional;

public record ObservationOutcome(
        ObservationOutcomeStatus status,
        ObservationDecision decision,
        Optional<ScreenContext> context,
        Optional<SemanticDiff> diff,
        String reason) {
    public ObservationOutcome {
        status = Objects.requireNonNull(status, "status must not be null");
        decision = Objects.requireNonNull(decision, "decision must not be null");
        context = context == null ? Optional.empty() : context;
        diff = diff == null ? Optional.empty() : diff;
        reason = reason == null ? "" : reason.trim();
        if (status == ObservationOutcomeStatus.AVAILABLE && context.isEmpty()) {
            throw new IllegalArgumentException("available outcome must contain context");
        }
        if (status != ObservationOutcomeStatus.AVAILABLE && (context.isPresent() || diff.isPresent())) {
            throw new IllegalArgumentException("unavailable outcome must not contain current context or diff");
        }
        if (status != ObservationOutcomeStatus.AVAILABLE && reason.isBlank()) {
            throw new IllegalArgumentException("non-available outcome must contain a reason");
        }
    }

    public static ObservationOutcome denied(ObservationDecision decision) {
        return new ObservationOutcome(
                ObservationOutcomeStatus.DENIED,
                decision,
                Optional.empty(),
                Optional.empty(),
                decision.reason().name());
    }

    public static ObservationOutcome unavailable(ObservationDecision decision, String reason) {
        return new ObservationOutcome(
                ObservationOutcomeStatus.UNAVAILABLE,
                decision,
                Optional.empty(),
                Optional.empty(),
                reason);
    }

    public static ObservationOutcome available(
            ObservationDecision decision,
            ScreenContext context,
            SemanticDiff diff) {
        return new ObservationOutcome(
                ObservationOutcomeStatus.AVAILABLE,
                decision,
                Optional.of(context),
                Optional.of(diff),
                "");
    }
}
