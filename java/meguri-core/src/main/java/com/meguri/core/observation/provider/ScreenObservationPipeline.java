package com.meguri.core.observation.provider;

import com.meguri.core.observation.privacy.ObservationDecision;
import com.meguri.core.observation.privacy.ObservationPolicy;
import com.meguri.core.observation.privacy.ObservationPolicyEvaluator;
import com.meguri.core.observation.screen.ScreenContext;
import com.meguri.core.observation.screen.SemanticDiff;
import com.meguri.core.observation.screen.SemanticDiffCalculator;
import com.meguri.core.observation.window.ObservedWindow;
import com.meguri.core.observation.window.WindowAttachmentState;

import java.time.Instant;
import java.util.Objects;

/** Policy-first orchestration boundary for semantic observation. */
public final class ScreenObservationPipeline {
    private final ObservationPolicyEvaluator policyEvaluator;
    private final SemanticDiffCalculator diffCalculator;

    public ScreenObservationPipeline() {
        this(new ObservationPolicyEvaluator(), new SemanticDiffCalculator());
    }

    public ScreenObservationPipeline(
            ObservationPolicyEvaluator policyEvaluator,
            SemanticDiffCalculator diffCalculator) {
        this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator must not be null");
        this.diffCalculator = Objects.requireNonNull(diffCalculator, "diffCalculator must not be null");
    }

    public ObservationOutcome observe(
            WindowAttachmentState attachment,
            ObservationPolicy policy,
            ScreenObservationProvider provider,
            ScreenContext previous,
            Instant now) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(now, "now must not be null");

        ObservationDecision decision = policyEvaluator.evaluate(attachment, policy);
        if (!decision.allowed()) {
            return ObservationOutcome.denied(decision);
        }

        ObservedWindow window = attachment.window().orElseThrow();
        ObservationProviderResult providerResult;
        try {
            providerResult = provider.observe(new ObservationRequest(window.windowId(), window, now));
        } catch (RuntimeException exception) {
            return ObservationOutcome.unavailable(decision, "provider_failed");
        }
        if (providerResult == null || providerResult.status() == ObservationProviderResult.Status.UNAVAILABLE) {
            String reason = providerResult == null ? "provider_returned_null" : providerResult.reason();
            return ObservationOutcome.unavailable(decision, reason);
        }

        ScreenContext current = providerResult.context().orElseThrow();
        if (!window.windowId().equals(current.scopeId())) {
            return ObservationOutcome.unavailable(decision, "scope_mismatch");
        }
        if (current.confidence() < policy.minimumConfidence()) {
            return ObservationOutcome.unavailable(decision, "confidence_below_threshold");
        }
        if (!current.isCurrentAt(now)) {
            return ObservationOutcome.unavailable(decision, "context_not_current");
        }

        ScreenContext previousCurrent = previous != null && previous.isCurrentAt(now)
                && previous.scopeId().equals(current.scopeId())
                ? previous
                : null;
        SemanticDiff diff = diffCalculator.compare(previousCurrent, current);
        return ObservationOutcome.available(decision, current, diff);
    }
}
