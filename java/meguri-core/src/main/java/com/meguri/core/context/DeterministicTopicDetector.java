package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Small, replayable detector used by default and in offline tests. */
public final class DeterministicTopicDetector implements TopicDetector {
    public static final String REVISION = "topic-detector-v1-deterministic";

    @Override
    public TopicSignal detect(String rawUserMessage,
                              List<SessionContextStore.MessageNode> activePath) {
        String input = rawUserMessage == null ? "" : rawUserMessage.strip();
        if (input.isBlank()) {
            return new TopicSignal("general", 0.2d, "empty raw input", REVISION);
        }
        String normalized = input.toLowerCase(Locale.ROOT);
        boolean explicitBoundary = normalized.contains("换个话题")
                || normalized.contains("另一个话题")
                || normalized.contains("new topic")
                || normalized.startsWith("topic:");
        String label = Arrays.stream(normalized.split("\\s+"))
                .filter(token -> !token.isBlank())
                .limit(3)
                .reduce((left, right) -> left + " " + right)
                .orElse("general");
        double confidence = explicitBoundary ? 0.85d : 0.55d;
        String reason = explicitBoundary
                ? "explicit raw-input boundary cue"
                : "deterministic raw-input lexical signal";
        return new TopicSignal(label, confidence, reason, REVISION);
    }
}
