package com.meguri.core.context;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.meguri.core.context.ContextRuntimePersistence.TopicSegment;
import static com.meguri.core.context.ContextRuntimePersistence.TopicStatus;

/** Creates reversible topic candidates; it never creates or moves a conversation. */
public final class TopicSegmentResolver {
    private final ContextRuntimePersistence persistence;

    public TopicSegmentResolver(ContextRuntimePersistence persistence) {
        this.persistence = persistence;
    }

    public TopicSegment resolve(String conversationId, String hint, double confidence) {
        List<TopicSegment> existing = persistence.findTopicSegments(conversationId);
        TopicSegment active = existing.stream()
                .filter(segment -> segment.status() == TopicStatus.ACTIVE)
                .max(Comparator.comparing(TopicSegment::createdAt)).orElse(null);
        String normalized = hint == null ? "" : hint.trim().toLowerCase(Locale.ROOT);
        if (active == null) {
            return persistence.saveTopicSegment(new TopicSegment(
                    id(), conversationId, normalized.isBlank() ? "general" : normalized,
                    TopicStatus.ACTIVE, Math.max(0, confidence), "initial context segment", Instant.now()));
        }
        if (!normalized.isBlank() && !normalized.equals(active.label()) && confidence >= 0.75d) {
            return existing.stream()
                    .filter(segment -> segment.status() == TopicStatus.CANDIDATE)
                    .filter(segment -> segment.label().equals(normalized))
                    .findFirst()
                    .orElseGet(() -> persistence.saveTopicSegment(new TopicSegment(
                            id(), conversationId, normalized, TopicStatus.CANDIDATE,
                            confidence, "detected soft topic switch", Instant.now())));
        }
        return active;
    }

    /** Withdraws a soft switch while keeping every raw message untouched. */
    public TopicSegment mergeCandidate(String conversationId, String segmentId) {
        TopicSegment candidate = find(conversationId, segmentId);
        if (candidate.status() != TopicStatus.CANDIDATE) {
            throw new IllegalStateException("only a candidate topic can be merged");
        }
        return persistence.saveTopicSegment(new TopicSegment(
                candidate.segmentId(), candidate.conversationId(), candidate.label(), TopicStatus.MERGED,
                candidate.confidence(), "user withdrew soft topic switch", candidate.createdAt()));
    }

    /** Accepts a sustained soft switch while preserving the previous segment as merged history. */
    public TopicSegment acceptCandidate(String conversationId, String segmentId) {
        TopicSegment candidate = find(conversationId, segmentId);
        if (candidate.status() != TopicStatus.CANDIDATE) {
            throw new IllegalStateException("only a candidate topic can become active");
        }
        persistence.findTopicSegments(conversationId).stream()
                .filter(segment -> segment.status() == TopicStatus.ACTIVE)
                .forEach(segment -> persistence.saveTopicSegment(new TopicSegment(
                        segment.segmentId(), segment.conversationId(), segment.label(), TopicStatus.MERGED,
                        segment.confidence(), "superseded by accepted topic candidate", segment.createdAt())));
        return persistence.saveTopicSegment(new TopicSegment(
                candidate.segmentId(), candidate.conversationId(), candidate.label(), TopicStatus.ACTIVE,
                candidate.confidence(), "accepted soft topic switch", candidate.createdAt()));
    }

    private TopicSegment find(String conversationId, String segmentId) {
        return persistence.findTopicSegments(conversationId).stream()
                .filter(segment -> segment.segmentId().equals(segmentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("topic segment does not exist"));
    }

    private static String id() {
        return "topic_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
