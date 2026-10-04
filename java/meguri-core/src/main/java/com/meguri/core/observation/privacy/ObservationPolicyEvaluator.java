package com.meguri.core.observation.privacy;

import com.meguri.core.observation.window.ObservedWindow;
import com.meguri.core.observation.window.WindowAttachmentState;

import java.util.Locale;
import java.util.Objects;

/** Applies privacy policy before any provider is allowed to inspect a window. */
public final class ObservationPolicyEvaluator {
    public ObservationDecision evaluate(WindowAttachmentState attachment, ObservationPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (!policy.enabled()) {
            return ObservationDecision.deny(ObservationDecisionReason.OBSERVATION_DISABLED);
        }
        if (policy.privacyMode()) {
            return ObservationDecision.deny(ObservationDecisionReason.PRIVACY_MODE);
        }
        if (attachment == null || !attachment.hasAttachment()) {
            return ObservationDecision.deny(ObservationDecisionReason.NO_ATTACHMENT);
        }
        if (!attachment.observationEligible()) {
            return ObservationDecision.deny(ObservationDecisionReason.WINDOW_UNAVAILABLE);
        }

        ObservedWindow window = attachment.window().orElseThrow();
        String appName = normalize(window.appName());
        String title = normalize(window.title());
        if (matchesExactOrContains(policy.blockedApplications(), appName)) {
            return ObservationDecision.deny(ObservationDecisionReason.APPLICATION_BLOCKED);
        }
        if (matchesContains(policy.blockedTitlePatterns(), title)) {
            return ObservationDecision.deny(ObservationDecisionReason.TITLE_BLOCKED);
        }
        if (policy.blockSensitiveWindows()
                && (window.sensitive()
                || matchesContains(policy.sensitiveApplicationPatterns(), appName)
                || matchesContains(policy.sensitiveTitlePatterns(), title))) {
            return ObservationDecision.deny(ObservationDecisionReason.SENSITIVE_WINDOW);
        }
        return ObservationDecision.allow();
    }

    private static boolean matchesExactOrContains(Iterable<String> patterns, String value) {
        for (String pattern : patterns) {
            if (value.equals(pattern) || value.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesContains(Iterable<String> patterns, String value) {
        for (String pattern : patterns) {
            if (value.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
