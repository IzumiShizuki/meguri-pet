package com.meguri.core.observation.privacy;

import com.meguri.core.observation.window.WindowAttachmentState;

import java.util.Optional;

/** Projects policy and attachment state without exposing a window title or content. */
public final class ObservationStatusProjection {
    private final ObservationPolicyEvaluator evaluator;

    public ObservationStatusProjection() {
        this(new ObservationPolicyEvaluator());
    }

    public ObservationStatusProjection(ObservationPolicyEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public ObservationStatusView project(
            WindowAttachmentState attachment,
            ObservationPolicy policy,
            boolean activelyObserving) {
        ObservationDecision decision = evaluator.evaluate(attachment, policy);
        Optional<String> windowId = attachment == null
                ? Optional.empty()
                : attachment.window().map(window -> window.windowId());
        Optional<String> applicationName = attachment == null
                ? Optional.empty()
                : attachment.window().map(window -> window.appName());

        ObservationStatus status;
        if (decision.reason() == ObservationDecisionReason.OBSERVATION_DISABLED
                || decision.reason() == ObservationDecisionReason.PRIVACY_MODE) {
            status = ObservationStatus.PAUSED;
        } else if (decision.reason() == ObservationDecisionReason.NO_ATTACHMENT
                || decision.reason() == ObservationDecisionReason.WINDOW_UNAVAILABLE) {
            status = ObservationStatus.UNAVAILABLE;
        } else if (!decision.allowed()) {
            status = ObservationStatus.DENIED;
        } else {
            status = activelyObserving ? ObservationStatus.ACTIVE : ObservationStatus.ELIGIBLE;
        }
        return new ObservationStatusView(status, decision.reason(), windowId, applicationName);
    }
}
