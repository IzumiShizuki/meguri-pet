package com.meguri.core.runtime;

import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.TurnRequest;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Internal persistence seam for turn acceptance, replay and idempotency. */
public interface TurnJournal {
    Acceptance accept(TurnRequest request, String idempotencyKey, Instant deadlineAt);

    default Acceptance acceptRetry(TurnRequest request, String idempotencyKey,
                                   Instant deadlineAt, String retryOfTurnId) {
        Acceptance acceptance = accept(request, idempotencyKey, deadlineAt);
        if (acceptance.created()) {
            acceptance.record().setRetryOfTurnId(retryOfTurnId);
            persist(acceptance.record());
        }
        return acceptance;
    }

    TurnRecord create(TurnRequest request, Instant deadlineAt);

    TurnRecord turn(String turnId);

    Map<String, TurnRecord> turns();

    List<EventEnvelope> events(String sessionId);

    default long firstSequence(String sessionId) {
        return events(sessionId).stream()
                .mapToLong(EventEnvelope::getSequence)
                .min()
                .orElse(0L);
    }

    long lastSequence(String sessionId);

    /**
     * Appends an event and persists the record's current lifecycle fields in the
     * same transaction when the journal is durable.
     */
    EventEnvelope append(TurnRecord record, String type, Map<String, Object> data, EventMetadata metadata);

    /**
     * Appends an execution event only while {@code ownerId} still owns a live
     * lease. Durable journals must fence expired workers in the same transaction
     * as the lifecycle update and event insert.
     */
    default EventEnvelope appendExecution(TurnRecord record, String ownerId, String type,
                                          Map<String, Object> data, EventMetadata metadata) {
        return append(record, type, data, metadata);
    }

    /** Persist the current lifecycle fields without creating a new event. */
    default void persist(TurnRecord record) {
        // In-memory journals already expose the live record.
    }

    default void persistExecution(TurnRecord record, String ownerId) {
        persist(record);
    }

    /** Claims execution with an expiring lease. Durable implementations use CAS. */
    default boolean claimExecution(TurnRecord record, String ownerId, Duration lease) {
        return true;
    }

    default boolean heartbeatExecution(TurnRecord record, String ownerId, Duration lease) {
        return true;
    }

    default void releaseExecution(TurnRecord record, String ownerId) {
        // In-memory execution is process-owned already.
    }

    /** Persists a cancellation intent without stealing the active execution lease. */
    default boolean requestCancellation(TurnRecord record) {
        return record.requestCancel();
    }

    /** Refreshes a durable cancellation intent into the local execution record. */
    default boolean refreshCancellation(TurnRecord record) {
        return record.isCancelRequested();
    }

    default List<OutboxMessage> claimOutbox(String ownerId, int limit, Duration lease) {
        return List.of();
    }

    default boolean heartbeatOutbox(long outboxId, String ownerId, Duration lease) {
        return false;
    }

    default boolean acknowledgeOutbox(long outboxId, String ownerId) {
        return false;
    }

    default boolean retryOutbox(long outboxId, String ownerId, String error,
                                Instant availableAt, int deadLetterAfter) {
        return false;
    }

    default List<OutboxMessage> deadLetterOutbox(int limit) {
        return List.of();
    }

    default boolean requeueOutbox(long outboxId, Instant availableAt) {
        return false;
    }

    void clear();

    record Acceptance(TurnRecord record, boolean created) { }

    record OutboxMessage(long outboxId, EventEnvelope event, int attempts) { }
}
