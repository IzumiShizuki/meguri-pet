package com.meguri.core.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.TurnRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic local adapter. A PostgreSQL adapter can replace it without changing callers. */
public final class InMemoryTurnJournal implements TurnJournal {
    private final ObjectMapper objectMapper;
    private final Map<String, CopyOnWriteArrayList<EventEnvelope>> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, TurnRecord> turns = new ConcurrentHashMap<>();
    private final Map<IdempotencyScope, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();

    public InMemoryTurnJournal(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    @Override
    public synchronized Acceptance accept(TurnRequest request, String idempotencyKey, Instant deadlineAt) {
        String normalized = normalize(idempotencyKey);
        if (normalized == null) return new Acceptance(create(request, deadlineAt), true);

        IdempotencyScope scope = new IdempotencyScope(
                request.getUserId(), request.getClientId(), request.getSessionId(), normalized);
        String payloadHash = payloadHash(request);
        IdempotencyEntry existing = idempotency.get(scope);
        if (existing != null) {
            if (!existing.payloadHash().equals(payloadHash)) throw new IdempotencyConflictException();
            return new Acceptance(turns.get(existing.turnId()), false);
        }
        TurnRecord record = create(request, deadlineAt);
        idempotency.put(scope, new IdempotencyEntry(record.getTurnId(), payloadHash));
        return new Acceptance(record, true);
    }

    @Override
    public synchronized Acceptance acceptRetry(TurnRequest request, String idempotencyKey,
                                                Instant deadlineAt, String retryOfTurnId) {
        TurnRecord predecessor = turns.get(retryOfTurnId);
        if (predecessor == null || predecessor.getStatus() != TurnStatus.FAILED
                || !sameScope(predecessor.getRequest(), request)) {
            throw new IllegalArgumentException(
                    "retry_of_turn_id must reference a failed Turn in the same scope");
        }
        Acceptance acceptance = accept(request, idempotencyKey, deadlineAt);
        if (!acceptance.created()) {
            if (!retryOfTurnId.equals(acceptance.record().getRetryOfTurnId())) {
                throw new IdempotencyConflictException();
            }
            return acceptance;
        }
        acceptance.record().setRetryOfTurnId(retryOfTurnId);
        return acceptance;
    }

    @Override
    public TurnRecord create(TurnRequest request, Instant deadlineAt) {
        TurnRecord record = new TurnRecord(newId("turn"), newId("trace"), request, deadlineAt);
        turns.put(record.getTurnId(), record);
        return record;
    }

    @Override
    public TurnRecord turn(String turnId) {
        return turns.get(turnId);
    }

    @Override
    public Map<String, TurnRecord> turns() {
        return Collections.unmodifiableMap(turns);
    }

    @Override
    public List<EventEnvelope> events(String sessionId) {
        List<EventEnvelope> value = events.get(sessionId);
        return value == null ? List.of() : List.copyOf(value);
    }

    @Override
    public long firstSequence(String sessionId) {
        List<EventEnvelope> value = events.get(sessionId);
        return value == null || value.isEmpty() ? 0L : value.getFirst().getSequence();
    }

    @Override
    public long lastSequence(String sessionId) {
        AtomicLong value = sequences.get(sessionId);
        return value == null ? 0L : value.get();
    }

    @Override
    public EventEnvelope append(TurnRecord record, String type, Map<String, Object> data, EventMetadata metadata) {
        String sessionId = record.getRequest().getSessionId();
        CopyOnWriteArrayList<EventEnvelope> sessionEvents =
                events.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>());
        synchronized (sessionEvents) {
            long next = sequences.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
            EventEnvelope event = new EventEnvelope(
                    EventEnvelope.CURRENT_PROTOCOL_VERSION,
                    newId("event"),
                    TurnEventTypes.isRequired(type),
                    null,
                    type,
                    record.getTurnId(),
                    sessionId,
                    next,
                    TurnEventTypes.replayPolicy(type, data),
                    metadata == null ? null : metadata.getCreatedAt(),
                    data,
                    metadata);
            sessionEvents.add(event);
            return event;
        }
    }

    @Override
    public EventEnvelope appendExecution(TurnRecord record, String ownerId, String type,
                                         Map<String, Object> data, EventMetadata metadata) {
        synchronized (record) {
            if (record.isTerminal()
                    && ("text.delta".equals(type) || "text.completed".equals(type))) {
                throw new IllegalStateException("cannot append " + type + " after the Turn is terminal");
            }
            return append(record, type, data, metadata);
        }
    }

    @Override
    public void clear() {
        turns.clear();
        events.clear();
        sequences.clear();
        idempotency.clear();
    }

    private String payloadHash(TurnRequest request) {
        try {
            return digest(objectMapper.writeValueAsBytes(request));
        } catch (JsonProcessingException error) {
            return digest(request.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String digest(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean sameScope(TurnRequest left, TurnRequest right) {
        return left.getUserId().equals(right.getUserId())
                && left.getClientId().equals(right.getClientId())
                && left.getSessionId().equals(right.getSessionId());
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record IdempotencyScope(String userId, String clientId, String sessionId, String key) { }
    private record IdempotencyEntry(String turnId, String payloadHash) { }
}
