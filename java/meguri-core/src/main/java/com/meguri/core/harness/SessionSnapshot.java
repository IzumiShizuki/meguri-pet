package com.meguri.core.harness;

import com.meguri.core.dto.EventEnvelope;

import java.time.Instant;
import java.util.List;

/** State-only recovery snapshot; ONCE events are deliberately excluded. */
public record SessionSnapshot(
        String sessionId,
        long lastSequence,
        List<TurnSnapshot> turns,
        List<EventEnvelope> stateEvents,
        List<String> processedEventIds,
        List<String> processedOnceEventIds,
        Instant generatedAt) {
    public SessionSnapshot(
            String sessionId,
            long lastSequence,
            List<TurnSnapshot> turns,
            List<EventEnvelope> stateEvents,
            Instant generatedAt) {
        this(sessionId, lastSequence, turns, stateEvents, List.of(), List.of(), generatedAt);
    }

    public SessionSnapshot {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (lastSequence < 0) throw new IllegalArgumentException("lastSequence must be non-negative");
        turns = turns == null ? List.of() : List.copyOf(turns);
        stateEvents = stateEvents == null ? List.of() : List.copyOf(stateEvents);
        processedEventIds = processedEventIds == null ? List.of() : List.copyOf(processedEventIds);
        processedOnceEventIds = processedOnceEventIds == null
                ? List.of() : List.copyOf(processedOnceEventIds);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
