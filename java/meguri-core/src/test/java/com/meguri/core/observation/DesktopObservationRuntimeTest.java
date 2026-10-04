package com.meguri.core.observation;

import com.meguri.core.observation.privacy.ObservationDecisionReason;
import com.meguri.core.observation.privacy.ObservationPolicy;
import com.meguri.core.observation.privacy.ObservationStatus;
import com.meguri.core.observation.privacy.ObservationStatusProjection;
import com.meguri.core.observation.provider.MockScreenObservationProvider;
import com.meguri.core.observation.provider.ObservationOutcomeStatus;
import com.meguri.core.observation.provider.ObservationProviderResult;
import com.meguri.core.observation.provider.ScreenObservationPipeline;
import com.meguri.core.observation.screen.ScreenContext;
import com.meguri.core.observation.screen.ScreenContextFreshness;
import com.meguri.core.observation.screen.ScreenObservationSource;
import com.meguri.core.observation.screen.SemanticDiffCalculator;
import com.meguri.core.observation.window.ObservedWindow;
import com.meguri.core.observation.window.WindowAnchor;
import com.meguri.core.observation.window.WindowAttachmentReducer;
import com.meguri.core.observation.window.WindowAttachmentState;
import com.meguri.core.observation.window.WindowAttachmentStatus;
import com.meguri.core.observation.window.WindowBounds;
import com.meguri.core.observation.window.WindowOffset;
import com.meguri.core.observation.window.WindowVisibility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopObservationRuntimeTest {
    private static final Instant T0 = Instant.parse("2026-08-11T01:00:00Z");

    @Test
    void attachmentFollowsTheSameWindowAndStopsObservationWhenMinimized() {
        WindowAttachmentState detached = WindowAttachmentState.detached("user-1", "desktop-1", T0);
        ObservedWindow visible = window("hwnd-1", "IntelliJ IDEA", "Meguri", WindowVisibility.VISIBLE, false);

        WindowAttachmentState attached = WindowAttachmentReducer.attach(detached, visible, T0.plusSeconds(1));
        assertThat(attached.status()).isEqualTo(WindowAttachmentStatus.ATTACHED);
        assertThat(attached.observationEligible()).isTrue();
        assertThat(attached.window()).get().extracting(ObservedWindow::windowId).isEqualTo("hwnd-1");

        ObservedWindow moved = new ObservedWindow(
                "hwnd-1", 100L, "IntelliJ IDEA", "Meguri",
                new WindowBounds(800, 240, 1200, 800),
                WindowAnchor.BOTTOM_RIGHT, new WindowOffset(12, 16),
                WindowVisibility.VISIBLE, false);
        WindowAttachmentState updated = WindowAttachmentReducer.update(attached, moved, T0.plusSeconds(2));
        assertThat(updated.window()).get().extracting(ObservedWindow::bounds)
                .isEqualTo(new WindowBounds(800, 240, 1200, 800));
        assertThat(updated.observationEligible()).isTrue();

        WindowAttachmentState minimized = WindowAttachmentReducer.update(
                updated,
                copyWithVisibility(moved, WindowVisibility.MINIMIZED),
                T0.plusSeconds(3));
        assertThat(minimized.status()).isEqualTo(WindowAttachmentStatus.UNAVAILABLE);
        assertThat(minimized.observationEligible()).isFalse();

        WindowAttachmentState restored = WindowAttachmentReducer.update(
                minimized,
                copyWithVisibility(moved, WindowVisibility.VISIBLE),
                T0.plusSeconds(4));
        assertThat(restored.status()).isEqualTo(WindowAttachmentStatus.ATTACHED);
        assertThat(restored.observationEligible()).isTrue();

        assertThatThrownBy(() -> WindowAttachmentReducer.update(
                restored,
                window("other-window", "Notepad", "Notes", WindowVisibility.VISIBLE, false),
                T0.plusSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currently attached window identity");

        WindowAttachmentState detachedAgain = WindowAttachmentReducer.detach(restored, T0.plusSeconds(6));
        assertThat(detachedAgain.status()).isEqualTo(WindowAttachmentStatus.DETACHED);
        assertThat(detachedAgain.window()).isEmpty();
    }

    @Test
    void privacyDenialsHappenBeforeTheProviderIsInvoked() {
        WindowAttachmentState attachment = attached(window("hwnd-1", "IntelliJ IDEA", "Meguri", false));
        ScreenContext context = context(attachment.window().orElseThrow().windowId(), T0.plusSeconds(1), .9,
                "compile error", Set.of("error_visible"));
        MockScreenObservationProvider provider = new MockScreenObservationProvider()
                .enqueue(ObservationProviderResult.available(context));
        ScreenObservationPipeline pipeline = new ScreenObservationPipeline();

        ObservationPolicy paused = new ObservationPolicy(
                false, false, Set.of(), Set.of(), true, Set.of(), Set.of(), .5, Duration.ofSeconds(30));
        var pausedOutcome = pipeline.observe(attachment, paused, provider, null, T0.plusSeconds(2));
        assertThat(pausedOutcome.status()).isEqualTo(ObservationOutcomeStatus.DENIED);
        assertThat(pausedOutcome.decision().reason()).isEqualTo(ObservationDecisionReason.OBSERVATION_DISABLED);
        assertThat(provider.invocationCount()).isZero();

        ObservationPolicy blocked = new ObservationPolicy(
                true, false, Set.of("idea"), Set.of(), true, Set.of(), Set.of(), .5, Duration.ofSeconds(30));
        var blockedOutcome = pipeline.observe(attachment, blocked, provider, null, T0.plusSeconds(2));
        assertThat(blockedOutcome.status()).isEqualTo(ObservationOutcomeStatus.DENIED);
        assertThat(blockedOutcome.decision().reason()).isEqualTo(ObservationDecisionReason.APPLICATION_BLOCKED);
        assertThat(provider.invocationCount()).isZero();

        WindowAttachmentState sensitiveAttachment = attached(
                window("hwnd-sensitive", "Password Manager", "Vault", false));
        var sensitiveOutcome = pipeline.observe(
                sensitiveAttachment, ObservationPolicy.defaults(), provider, null, T0.plusSeconds(2));
        assertThat(sensitiveOutcome.status()).isEqualTo(ObservationOutcomeStatus.DENIED);
        assertThat(sensitiveOutcome.decision().reason()).isEqualTo(ObservationDecisionReason.SENSITIVE_WINDOW);
        assertThat(provider.invocationCount()).isZero();
    }

    @Test
    void statusProjectionDoesNotExposeWindowTitleOrContent() {
        WindowAttachmentState attachment = attached(window("hwnd-1", "IntelliJ IDEA", "private content", false));
        ObservationStatusProjection projection = new ObservationStatusProjection();

        var eligible = projection.project(attachment, ObservationPolicy.defaults(), false);
        assertThat(eligible.status()).isEqualTo(ObservationStatus.ELIGIBLE);
        assertThat(eligible.windowId()).contains("hwnd-1");
        assertThat(eligible.applicationName()).contains("IntelliJ IDEA");

        var active = projection.project(attachment, ObservationPolicy.defaults(), true);
        assertThat(active.status()).isEqualTo(ObservationStatus.ACTIVE);

        var privacy = projection.project(
                attachment,
                new ObservationPolicy(true, true, Set.of(), Set.of(), true, Set.of(), Set.of(), .5,
                        Duration.ofSeconds(30)),
                false);
        assertThat(privacy.status()).isEqualTo(ObservationStatus.PAUSED);
        assertThat(privacy.windowId()).contains("hwnd-1");
        assertThat(privacy.applicationName()).contains("IntelliJ IDEA");
        assertThat(privacy.toString()).doesNotContain("private content");
    }

    @Test
    void screenContextUsesExplicitFreshnessAndMeaningfulDiffs() {
        ScreenContext previous = context("hwnd-1", T0, .9, "writing   Java", Set.of("editing"));
        ScreenContext equivalent = context("hwnd-1", T0.plusSeconds(5), .9, "writing Java", Set.of("editing"));
        ScreenContext error = context("hwnd-1", T0.plusSeconds(10), .9, "compile error", Set.of("editing", "error_visible"));
        ScreenContext fixed = context("hwnd-1", T0.plusSeconds(15), .9, "tests passing", Set.of("editing"));

        assertThat(ScreenContextFreshness.classify(previous, T0.plusSeconds(1)))
                .isEqualTo(ScreenContextFreshness.CURRENT);
        assertThat(ScreenContextFreshness.classify(previous, T0.plusSeconds(30)))
                .isEqualTo(ScreenContextFreshness.RECENT_HISTORY);
        assertThat(ScreenContextFreshness.classify(previous, T0.minusSeconds(1)))
                .isEqualTo(ScreenContextFreshness.UNAVAILABLE);

        var calculator = new SemanticDiffCalculator();
        assertThat(calculator.compare(previous, equivalent).meaningful()).isFalse();
        assertThat(calculator.compare(previous, error).addedSignals()).containsExactly("error_visible");
        assertThat(calculator.compare(error, fixed).removedSignals()).containsExactly("error_visible");
        assertThat(calculator.compare(null, previous).meaningful()).isTrue();
        assertThatThrownBy(() -> calculator.compare(previous, context(
                "other-window", T0.plusSeconds(2), .9, "different", Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same observation scope");
    }

    @Test
    void pipelineRejectsLowConfidenceAndProviderFailuresWithoutStalePromotion() {
        WindowAttachmentState attachment = attached(window("hwnd-1", "IntelliJ IDEA", "Meguri", false));
        ScreenObservationPipeline pipeline = new ScreenObservationPipeline();
        ObservationPolicy policy = new ObservationPolicy(
                true, false, Set.of(), Set.of(), true, Set.of(), Set.of(), .8, Duration.ofSeconds(30));

        MockScreenObservationProvider lowConfidence = new MockScreenObservationProvider()
                .enqueue(ObservationProviderResult.available(
                        context("hwnd-1", T0.plusSeconds(1), .7, "uncertain", Set.of())));
        var lowConfidenceOutcome = pipeline.observe(attachment, policy, lowConfidence, null, T0.plusSeconds(2));
        assertThat(lowConfidenceOutcome.status()).isEqualTo(ObservationOutcomeStatus.UNAVAILABLE);
        assertThat(lowConfidenceOutcome.reason()).isEqualTo("confidence_below_threshold");

        MockScreenObservationProvider freshProvider = new MockScreenObservationProvider()
                .enqueue(ObservationProviderResult.available(
                        context("hwnd-1", T0.plusSeconds(32), .9, "tests passing", Set.of())));
        var freshOutcome = pipeline.observe(
                attachment,
                policy,
                freshProvider,
                context("hwnd-1", T0, .9, "stale error", Set.of("error_visible")),
                T0.plusSeconds(33));
        assertThat(freshOutcome.status()).isEqualTo(ObservationOutcomeStatus.AVAILABLE);
        assertThat(freshOutcome.diff()).get().extracting(diff -> diff.initial()).isEqualTo(true);

        var failedOutcome = pipeline.observe(
                attachment,
                policy,
                request -> {
                    throw new IllegalStateException("native provider is unavailable");
                },
                context("hwnd-1", T0, .9, "stale error", Set.of("error_visible")),
                T0.plusSeconds(31));
        assertThat(failedOutcome.status()).isEqualTo(ObservationOutcomeStatus.UNAVAILABLE);
        assertThat(failedOutcome.reason()).isEqualTo("provider_failed");
        assertThat(failedOutcome.context()).isEmpty();
    }

    private static WindowAttachmentState attached(ObservedWindow window) {
        return WindowAttachmentReducer.attach(
                WindowAttachmentState.detached("user-1", "desktop-1", T0), window, T0.plusSeconds(1));
    }

    private static ObservedWindow window(
            String windowId,
            String appName,
            String title,
            boolean sensitive) {
        return window(windowId, appName, title, WindowVisibility.VISIBLE, sensitive);
    }

    private static ObservedWindow window(
            String windowId,
            String appName,
            String title,
            WindowVisibility visibility,
            boolean sensitive) {
        return new ObservedWindow(
                windowId,
                100L,
                appName,
                title,
                new WindowBounds(100, 100, 800, 600),
                WindowAnchor.BOTTOM_RIGHT,
                new WindowOffset(12, 16),
                visibility,
                sensitive);
    }

    private static ObservedWindow copyWithVisibility(ObservedWindow source, WindowVisibility visibility) {
        return new ObservedWindow(
                source.windowId(), source.processId(), source.appName(), source.title(), source.bounds(),
                source.anchor(), source.offset(), visibility, source.sensitive());
    }

    private static ScreenContext context(
            String scopeId,
            Instant observedAt,
            double confidence,
            String content,
            Set<String> signals) {
        return new ScreenContext(
                scopeId,
                "IntelliJ IDEA",
                "debugging",
                content,
                signals,
                ScreenObservationSource.MOCK,
                confidence,
                observedAt,
                observedAt.plusSeconds(30));
    }
}
