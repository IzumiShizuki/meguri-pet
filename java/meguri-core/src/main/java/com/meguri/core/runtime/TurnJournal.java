package com.meguri.core.runtime;

import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.TurnRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Internal persistence seam for turn acceptance, replay and idempotency. */
public interface TurnJournal {
    Acceptance accept(TurnRequest request, String idempotencyKey, Instant deadlineAt);

    TurnRecord create(TurnRequest request, Instant deadlineAt);

    TurnRecord turn(String turnId);

    Map<String, TurnRecord> turns();

    List<EventEnvelope> events(String sessionId);

    long lastSequence(String sessionId);

    /**
     * Appends an event and persists the record's current lifecycle fields in the
     * same transaction when the journal is durable.
     */
    EventEnvelope append(TurnRecord record, String type, Map<String, Object> data, EventMetadata metadata);

    /** Persist the current lifecycle fields without creating a new event. */
    default void persist(TurnRecord record) {
        // In-memory journals already expose the live record.
    }

    void clear();

    record Acceptance(TurnRecord record, boolean created) { }
}
