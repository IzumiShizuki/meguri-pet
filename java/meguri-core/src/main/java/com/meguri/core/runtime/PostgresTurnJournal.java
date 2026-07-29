package com.meguri.core.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.HarnessManifest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** PostgreSQL-backed Turn journal with atomic event/outbox writes. */
public final class PostgresTurnJournal implements TurnJournal {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final String TURN_COLUMNS = """
            turn_id, trace_id, request_json, status, stage, accepted_at,
            deadline_at, manifest_json, result_json, failure_code, error,
            retry_of_turn_id, cancel_requested, version
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final String recoveryBuildId;
    private final Map<String, TurnRecord> turns = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<EventEnvelope>> events = new ConcurrentHashMap<>();

    public PostgresTurnJournal(JdbcTemplate jdbc, ObjectMapper objectMapper,
                               TransactionTemplate transactions, String recoveryBuildId) {
        this(jdbc, objectMapper, transactions, recoveryBuildId, true, true);
    }

    PostgresTurnJournal(JdbcTemplate jdbc, ObjectMapper objectMapper,
                        TransactionTemplate transactions, String recoveryBuildId,
                        boolean initializeSchema, boolean recoverInterrupted) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.recoveryBuildId = required(recoveryBuildId, "recoveryBuildId");
        if (initializeSchema) initializeSchema();
        if (initializeSchema || recoverInterrupted) loadState();
        if (recoverInterrupted) recoverInterruptedTurns();
    }

    @Override
    public synchronized Acceptance accept(TurnRequest request, String idempotencyKey, Instant deadlineAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        String normalizedKey = normalize(idempotencyKey);
        String payloadHash = payloadHash(request);
        Acceptance acceptance = transactions.execute(status -> {
            if (normalizedKey != null) {
                IdempotencyRow existing = findIdempotency(request, normalizedKey, true);
                if (existing != null) return existingAcceptance(existing, payloadHash);
            }

            TurnRecord candidate = newRecord(request, deadlineAt);
            int inserted = insert(candidate, normalizedKey, payloadHash, normalizedKey != null);
            if (inserted == 0) {
                IdempotencyRow existing = findIdempotency(request, normalizedKey, true);
                if (existing == null) {
                    throw new IllegalStateException("idempotent turn insert conflicted without an existing row");
                }
                return existingAcceptance(existing, payloadHash);
            }
            return new Acceptance(candidate, true);
        });
        if (acceptance == null) throw new IllegalStateException("turn acceptance transaction returned no result");
        if (acceptance.created()) turns.put(acceptance.record().getTurnId(), acceptance.record());
        return acceptance;
    }

    @Override
    public synchronized Acceptance acceptRetry(TurnRequest request, String idempotencyKey,
                                                Instant deadlineAt, String retryOfTurnId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        String predecessorId = required(retryOfTurnId, "retryOfTurnId");
        String normalizedKey = normalize(idempotencyKey);
        String requestHash = payloadHash(request);
        Acceptance acceptance = transactions.execute(status -> {
            Integer failedParent = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM turn_runtime
                    WHERE turn_id = ? AND status = 'failed'
                      AND user_id = ? AND client_id = ? AND session_id = ?
                    """, Integer.class, predecessorId, request.getUserId(),
                    request.getClientId(), request.getSessionId());
            if (failedParent == null || failedParent != 1) {
                throw new IllegalArgumentException(
                        "retry_of_turn_id must reference a failed Turn in the same scope");
            }
            if (normalizedKey != null) {
                IdempotencyRow existing = findIdempotency(request, normalizedKey, true);
                if (existing != null) {
                    Acceptance replay = existingAcceptance(existing, requestHash);
                    if (!predecessorId.equals(replay.record().getRetryOfTurnId())) {
                        throw new IdempotencyConflictException();
                    }
                    return replay;
                }
            }
            TurnRecord candidate = newRecord(request, deadlineAt);
            candidate.setRetryOfTurnId(predecessorId);
            int inserted = insert(candidate, normalizedKey, requestHash, normalizedKey != null);
            if (inserted == 0) {
                IdempotencyRow existing = findIdempotency(request, normalizedKey, true);
                if (existing == null) throw new IllegalStateException(
                        "retry insert conflicted without an existing row");
                Acceptance replay = existingAcceptance(existing, requestHash);
                if (!predecessorId.equals(replay.record().getRetryOfTurnId())) {
                    throw new IdempotencyConflictException();
                }
                return replay;
            }
            return new Acceptance(candidate, true);
        });
        if (acceptance == null) throw new IllegalStateException("retry acceptance returned no result");
        if (acceptance.created()) turns.put(acceptance.record().getTurnId(), acceptance.record());
        return acceptance;
    }

    @Override
    public synchronized TurnRecord create(TurnRequest request, Instant deadlineAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        TurnRecord record = newRecord(request, deadlineAt);
        transactions.executeWithoutResult(status -> insert(record, null, payloadHash(request), false));
        turns.put(record.getTurnId(), record);
        return record;
    }

    @Override
    public TurnRecord turn(String turnId) {
        if (turnId == null || turnId.isBlank()) return null;
        List<TurnRecord> records = jdbc.query(
                "SELECT " + TURN_COLUMNS + " FROM turn_runtime WHERE turn_id = ?",
                (rs, rowNum) -> readRecord(rs), turnId);
        if (records.isEmpty()) {
            turns.remove(turnId);
            return null;
        }
        return refreshCached(records.getFirst());
    }

    @Override
    public synchronized Map<String, TurnRecord> turns() {
        List<TurnRecord> records = jdbc.query(
                "SELECT " + TURN_COLUMNS + " FROM turn_runtime ORDER BY accepted_at, turn_id",
                (rs, rowNum) -> readRecord(rs));
        LinkedHashMap<String, TurnRecord> authoritative = new LinkedHashMap<>();
        for (TurnRecord record : records) {
            TurnRecord refreshed = refreshCached(record);
            authoritative.put(refreshed.getTurnId(), refreshed);
        }
        turns.keySet().retainAll(authoritative.keySet());
        return Collections.unmodifiableMap(authoritative);
    }

    @Override
    public List<EventEnvelope> events(String sessionId) {
        return jdbc.query("""
                        SELECT event_id, protocol_version, required, required_extension,
                               event_type, turn_id, session_id, sequence, replay_policy,
                               data_json, metadata_json, created_at
                        FROM turn_event
                        WHERE session_id = ?
                        ORDER BY sequence
                        """,
                (rs, rowNum) -> readEvent(rs), sessionId);
    }

    @Override
    public long firstSequence(String sessionId) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MIN(sequence), 0) FROM turn_event WHERE session_id = ?",
                Long.class, sessionId);
        return value == null ? 0L : value;
    }

    @Override
    public long lastSequence(String sessionId) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM turn_event WHERE session_id = ?",
                Long.class, sessionId);
        return value == null ? 0L : value;
    }

    @Override
    public EventEnvelope append(TurnRecord record, String type,
                                Map<String, Object> data, EventMetadata metadata) {
        synchronized (record) {
            return appendLocked(record, null, type, data, metadata);
        }
    }

    @Override
    public EventEnvelope appendExecution(TurnRecord record, String ownerId, String type,
                                         Map<String, Object> data, EventMetadata metadata) {
        synchronized (record) {
            return appendLocked(record, required(ownerId, "ownerId"), type, data, metadata);
        }
    }

    private EventEnvelope appendLocked(TurnRecord record, String ownerId, String type,
                                       Map<String, Object> data, EventMetadata metadata) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(metadata, "metadata");
        long versionBefore = record.getVersion();
        EventEnvelope event;
        try {
            event = transactions.execute(status -> appendInTransaction(
                    record, ownerId, type, data == null ? Map.of() : data, metadata));
        } catch (Throwable error) {
            record.setVersion(versionBefore);
            throw error;
        }
        if (event == null) throw new IllegalStateException("event transaction returned no event");
        events.computeIfAbsent(event.getSessionId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
        return event;
    }

    @Override
    public void persist(TurnRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (record) {
            transactions.executeWithoutResult(status -> persistInTransaction(record, null));
        }
    }

    @Override
    public void persistExecution(TurnRecord record, String ownerId) {
        Objects.requireNonNull(record, "record");
        synchronized (record) {
            transactions.executeWithoutResult(status -> persistInTransaction(
                    record, required(ownerId, "ownerId")));
        }
    }

    @Override
    public boolean claimExecution(TurnRecord record, String ownerId, Duration lease) {
        Objects.requireNonNull(record, "record");
        synchronized (record) {
            Instant until = Instant.now().plus(positive(lease));
            int updated = jdbc.update("""
                UPDATE turn_runtime
                SET owner_id = ?, lease_until = ?, heartbeat_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE turn_id = ? AND version = ? AND status NOT IN ('completed', 'failed', 'cancelled')
                  AND (owner_id = ? OR lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                """, required(ownerId, "ownerId"), Timestamp.from(until), record.getTurnId(),
                record.getVersion(), ownerId);
            if (updated == 1) record.setVersion(record.getVersion() + 1);
            return updated == 1;
        }
    }

    @Override
    public boolean heartbeatExecution(TurnRecord record, String ownerId, Duration lease) {
        synchronized (record) {
            Instant until = Instant.now().plus(positive(lease));
            int updated = jdbc.update("""
                UPDATE turn_runtime
                SET lease_until = ?, heartbeat_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE turn_id = ? AND owner_id = ? AND version = ?
                  AND lease_until > CURRENT_TIMESTAMP
                  AND status NOT IN ('completed', 'failed', 'cancelled')
                """, Timestamp.from(until), record.getTurnId(), required(ownerId, "ownerId"),
                record.getVersion());
            if (updated == 1) record.setVersion(record.getVersion() + 1);
            return updated == 1;
        }
    }

    @Override
    public void releaseExecution(TurnRecord record, String ownerId) {
        synchronized (record) {
            int updated = jdbc.update("""
                UPDATE turn_runtime
                SET owner_id = NULL, lease_until = NULL, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE turn_id = ? AND owner_id = ? AND version = ?
                """, record.getTurnId(), required(ownerId, "ownerId"), record.getVersion());
            if (updated == 1) record.setVersion(record.getVersion() + 1);
        }
    }

    @Override
    public boolean requestCancellation(TurnRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (record) {
            List<Long> versions = jdbc.query("""
                            UPDATE turn_runtime
                            SET cancel_requested = TRUE, version = version + 1,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE turn_id = ?
                              AND status NOT IN ('completed', 'failed', 'cancelled')
                              AND cancel_requested = FALSE
                            RETURNING version
                            """,
                    (rs, rowNum) -> rs.getLong("version"), record.getTurnId());
            if (!versions.isEmpty()) {
                record.setVersion(versions.getFirst());
                record.requestCancel();
                return true;
            }
            return refreshCancellation(record);
        }
    }

    @Override
    public boolean refreshCancellation(TurnRecord record) {
        Objects.requireNonNull(record, "record");
        List<CancellationState> states = jdbc.query("""
                        SELECT cancel_requested, version
                        FROM turn_runtime
                        WHERE turn_id = ?
                        """,
                (rs, rowNum) -> new CancellationState(
                        rs.getBoolean("cancel_requested"), rs.getLong("version")),
                record.getTurnId());
        if (states.isEmpty() || !states.getFirst().requested()) return false;
        synchronized (record) {
            CancellationState state = states.getFirst();
            if (state.version() > record.getVersion()) record.setVersion(state.version());
            record.requestCancel();
            return true;
        }
    }

    @Override
    public List<OutboxMessage> claimOutbox(String ownerId, int limit, Duration lease) {
        String owner = required(ownerId, "ownerId");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Instant until = Instant.now().plus(positive(lease));
        List<Long> ids = transactions.execute(status -> {
            List<Long> selected = jdbc.queryForList("""
                    SELECT outbox_id FROM turn_outbox
                    WHERE (status = 'pending' AND available_at <= CURRENT_TIMESTAMP)
                       OR (status = 'claimed' AND lease_until < CURRENT_TIMESTAMP)
                    ORDER BY outbox_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """, Long.class, limit);
            for (Long id : selected) {
                jdbc.update("""
                        UPDATE turn_outbox
                        SET status = 'claimed', claim_owner = ?, claimed_at = CURRENT_TIMESTAMP,
                            lease_until = ?, attempts = attempts + 1
                        WHERE outbox_id = ?
                        """, owner, Timestamp.from(until), id);
            }
            return selected;
        });
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(id -> jdbc.queryForObject("""
                        SELECT o.outbox_id, o.attempts,
                               e.event_id, e.protocol_version, e.required, e.required_extension,
                               e.event_type, e.turn_id, e.session_id, e.sequence, e.replay_policy,
                               e.data_json, e.metadata_json, e.created_at
                        FROM turn_outbox o JOIN turn_event e ON e.event_id = o.event_id
                        WHERE o.outbox_id = ? AND o.claim_owner = ? AND o.status = 'claimed'
                        """, (rs, rowNum) -> new OutboxMessage(
                                rs.getLong("outbox_id"), readEvent(rs), rs.getInt("attempts")),
                        id, owner))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public boolean heartbeatOutbox(long outboxId, String ownerId, Duration lease) {
        return jdbc.update("""
                UPDATE turn_outbox SET lease_until = ?
                WHERE outbox_id = ? AND status = 'claimed' AND claim_owner = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, Timestamp.from(Instant.now().plus(positive(lease))), outboxId,
                required(ownerId, "ownerId")) == 1;
    }

    @Override
    public boolean acknowledgeOutbox(long outboxId, String ownerId) {
        return jdbc.update("""
                UPDATE turn_outbox
                SET status = 'delivered', delivered_at = CURRENT_TIMESTAMP,
                    claim_owner = NULL, lease_until = NULL, last_error = NULL
                WHERE outbox_id = ? AND status = 'claimed' AND claim_owner = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, outboxId, required(ownerId, "ownerId")) == 1;
    }

    @Override
    public boolean retryOutbox(long outboxId, String ownerId, String error,
                               Instant availableAt, int deadLetterAfter) {
        if (deadLetterAfter < 1) throw new IllegalArgumentException("deadLetterAfter must be positive");
        return jdbc.update("""
                UPDATE turn_outbox
                SET status = CASE WHEN attempts >= ? THEN 'dead_letter' ELSE 'pending' END,
                    available_at = ?, claim_owner = NULL, claimed_at = NULL,
                    lease_until = NULL, last_error = ?
                WHERE outbox_id = ? AND status = 'claimed' AND claim_owner = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, deadLetterAfter, Timestamp.from(Objects.requireNonNull(availableAt, "availableAt")),
                error, outboxId, required(ownerId, "ownerId")) == 1;
    }

    @Override
    public List<OutboxMessage> deadLetterOutbox(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return jdbc.query("""
                        SELECT o.outbox_id, o.attempts,
                               e.event_id, e.protocol_version, e.required, e.required_extension,
                               e.event_type, e.turn_id, e.session_id, e.sequence, e.replay_policy,
                               e.data_json, e.metadata_json, e.created_at
                        FROM turn_outbox o JOIN turn_event e ON e.event_id = o.event_id
                        WHERE o.status = 'dead_letter'
                        ORDER BY o.outbox_id
                        LIMIT ?
                        """,
                (rs, rowNum) -> new OutboxMessage(
                        rs.getLong("outbox_id"), readEvent(rs), rs.getInt("attempts")),
                limit);
    }

    @Override
    public boolean requeueOutbox(long outboxId, Instant availableAt) {
        return jdbc.update("""
                UPDATE turn_outbox
                SET status = 'pending', attempts = 0, available_at = ?,
                    claim_owner = NULL, claimed_at = NULL, lease_until = NULL,
                    delivered_at = NULL
                WHERE outbox_id = ? AND status = 'dead_letter'
                """, Timestamp.from(Objects.requireNonNull(availableAt, "availableAt")),
                outboxId) == 1;
    }

    @Override
    public synchronized void clear() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM turn_outbox");
            jdbc.update("DELETE FROM turn_event");
            jdbc.update("DELETE FROM turn_session_sequence");
            jdbc.update("DELETE FROM turn_runtime");
        });
        turns.clear();
        events.clear();
    }

    private void initializeSchema() {
        if (jdbc.getDataSource() == null) throw new IllegalStateException("PostgreSQL journal requires a datasource");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/turn-runtime.sql"));
        populator.setContinueOnError(false);
        populator.execute(jdbc.getDataSource());
    }

    private void loadState() {
        turns.clear();
        jdbc.query("SELECT " + TURN_COLUMNS + " FROM turn_runtime ORDER BY accepted_at, turn_id",
                (ResultSet rs) -> {
                    TurnRecord record = readRecord(rs);
                    turns.put(record.getTurnId(), record);
                });

        events.clear();
        jdbc.query("""
                        SELECT event_id, protocol_version, required, required_extension,
                               event_type, turn_id, session_id, sequence, replay_policy,
                               data_json, metadata_json, created_at
                        FROM turn_event
                        ORDER BY session_id, sequence
                        """,
                (ResultSet rs) -> {
                    EventEnvelope event = readEvent(rs);
                    events.computeIfAbsent(event.getSessionId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
                });
    }

    void recoverInterruptedTurns() {
        List<TurnRecord> interrupted = jdbc.query("""
                        SELECT """ + TURN_COLUMNS + """
                        FROM turn_runtime
                        WHERE status NOT IN ('completed', 'failed', 'cancelled')
                          AND (owner_id IS NULL OR lease_until IS NULL
                               OR lease_until < CURRENT_TIMESTAMP)
                        ORDER BY accepted_at, turn_id
                        """, (rs, rowNum) -> readRecord(rs)).stream()
                .map(this::refreshCached)
                .toList();
        for (TurnRecord record : interrupted) {
            String recoveryOwner = "recovery-" + UUID.randomUUID();
            if (!claimExecution(record, recoveryOwner, Duration.ofSeconds(30))) continue;
            TurnStage interruptedStage = record.getStage();
            if (record.isCancelRequested()) {
                if (record.tryCancel()) {
                    try {
                        appendExecution(record, recoveryOwner, "turn.cancelled",
                                Map.of("reason", "client_requested",
                                        "interrupted_stage", interruptedStage.wireValue(),
                                        "recovered_after_restart", true),
                                new EventMetadata(record.getTraceId(), "meguri-core",
                                        Instant.now(), recoveryBuildId));
                    } finally {
                        releaseExecution(record, recoveryOwner);
                    }
                    record.completeDone();
                } else {
                    releaseExecution(record, recoveryOwner);
                }
                continue;
            }
            String failureCode = interruptedStage == TurnStage.GENERATING
                    ? "PROVIDER_STREAM_INTERRUPTED" : "TURN_EXECUTION_INTERRUPTED";
            String error = "turn interrupted during " + interruptedStage.wireValue()
                    + " by meguri-core restart";
            if (!record.tryFail(failureCode, error)) {
                releaseExecution(record, recoveryOwner);
                continue;
            }
            try {
                appendExecution(record, recoveryOwner, "turn.failed",
                        Map.of("error", record.getError(),
                                "failure_code", record.getFailureCode(),
                                "interrupted_stage", interruptedStage.wireValue(),
                                "recovered_after_restart", true),
                        new EventMetadata(record.getTraceId(), "meguri-core", Instant.now(), recoveryBuildId));
            } finally {
                releaseExecution(record, recoveryOwner);
            }
            record.completeDone();
        }
    }

    private EventEnvelope appendInTransaction(TurnRecord record, String ownerId, String type,
                                              Map<String, Object> data, EventMetadata metadata) {
        persistInTransaction(record, ownerId);
        String sessionId = record.getRequest().getSessionId();
        jdbc.update("""
                INSERT INTO turn_session_sequence (session_id, last_sequence)
                VALUES (?, 0)
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
        Long next = jdbc.queryForObject("""
                UPDATE turn_session_sequence
                SET last_sequence = last_sequence + 1
                WHERE session_id = ?
                RETURNING last_sequence
                """, Long.class, sessionId);
        if (next == null || next < 1) throw new IllegalStateException("failed to allocate session sequence");

        EventEnvelope event = new EventEnvelope(
                EventEnvelope.CURRENT_PROTOCOL_VERSION,
                newId("event"),
                TurnEventTypes.isRequired(type),
                null,
                type,
                record.getTurnId(),
                sessionId,
                next.longValue(),
                TurnEventTypes.replayPolicy(type, data),
                metadata.getCreatedAt(),
                data,
                metadata);
        jdbc.update("""
                INSERT INTO turn_event (
                    event_id, turn_id, session_id, sequence, protocol_version,
                    required, required_extension, event_type, replay_policy,
                    data_json, metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """,
                event.getEventId(), event.getTurnId(), event.getSessionId(), event.getSequence(),
                event.getProtocolVersion(), event.isRequired(), event.getRequiredExtension(),
                event.getType(), event.getReplayPolicy().name(), write(event.getData()),
                write(event.getMetadata()), Timestamp.from(event.getCreatedAt()));
        jdbc.update("""
                INSERT INTO turn_outbox (event_id, aggregate_id, session_id, payload_json)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """, event.getEventId(), event.getTurnId(), event.getSessionId(), write(event));
        return event;
    }

    private void persistInTransaction(TurnRecord record, String ownerId) {
        String ownerFence = ownerId == null ? "" : """
                 AND owner_id = ? AND lease_until > CURRENT_TIMESTAMP
                """;
        String terminalFence = record.isTerminal() ? "" : """
                 AND status NOT IN ('completed', 'failed', 'cancelled')
                """;
        String cancellationFence = record.getStatus() == TurnStatus.CANCELLED ? "" : """
                 AND cancel_requested = FALSE
                """;
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>(java.util.Arrays.asList(
                record.statusValue(), record.getStage().wireValue(), nullableJson(record.getManifest()),
                nullableJson(record.getResult()), record.getFailureCode(), record.getError(),
                record.getRetryOfTurnId(), record.isCancelRequested(),
                record.getTurnId(), record.getVersion()));
        if (ownerId != null) parameters.add(ownerId);
        int updated = jdbc.update("""
                UPDATE turn_runtime
                SET status = ?, stage = ?, manifest_json = CAST(? AS jsonb),
                    result_json = CAST(? AS jsonb), failure_code = ?, error = ?,
                    retry_of_turn_id = ?, cancel_requested = ?, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE turn_id = ? AND version = ?
                """ + terminalFence + cancellationFence + ownerFence, parameters.toArray());
        if (updated != 1) throw new IllegalStateException(
                ownerId == null
                        ? "turn lifecycle CAS failed for " + record.getTurnId()
                        : "turn execution lease lost for " + record.getTurnId());
        record.setVersion(record.getVersion() + 1);
    }

    private int insert(TurnRecord record, String idempotencyKey, String payloadHash, boolean ignoreConflict) {
        String conflict = ignoreConflict
                ? " ON CONFLICT (user_id, client_id, session_id, idempotency_key) "
                        + "WHERE idempotency_key IS NOT NULL DO NOTHING"
                : "";
        TurnRequest request = record.getRequest();
        return jdbc.update("""
                INSERT INTO turn_runtime (
                    turn_id, trace_id, user_id, client_id, session_id, idempotency_key,
                    payload_hash, request_json, status, stage, accepted_at, deadline_at,
                    retry_of_turn_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, 0)
                """ + conflict,
                record.getTurnId(), record.getTraceId(), request.getUserId(), request.getClientId(),
                request.getSessionId(), idempotencyKey, payloadHash, write(request),
                record.statusValue(), record.getStage().wireValue(), Timestamp.from(record.getAcceptedAt()),
                Timestamp.from(record.getDeadlineAt()), record.getRetryOfTurnId());
    }

    private IdempotencyRow findIdempotency(TurnRequest request, String key, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<IdempotencyRow> rows = jdbc.query("""
                        SELECT turn_id, payload_hash
                        FROM turn_runtime
                        WHERE user_id = ? AND client_id = ? AND session_id = ? AND idempotency_key = ?
                        """ + suffix,
                (rs, rowNum) -> new IdempotencyRow(rs.getString("turn_id"), rs.getString("payload_hash")),
                request.getUserId(), request.getClientId(), request.getSessionId(), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Acceptance existingAcceptance(IdempotencyRow existing, String payloadHash) {
        if (!Objects.equals(existing.payloadHash(), payloadHash)) throw new IdempotencyConflictException();
        TurnRecord record = turn(existing.turnId());
        if (record == null) throw new IllegalStateException("idempotent turn row disappeared");
        return new Acceptance(record, false);
    }

    private TurnRecord refreshCached(TurnRecord fresh) {
        return turns.compute(fresh.getTurnId(), (ignored, current) -> {
            if (current == null) return fresh;
            synchronized (current) {
                // A local writer may have changed the object immediately before its
                // fenced persist. Only a newer durable version may overwrite it.
                if (fresh.getVersion() <= current.getVersion()) return current;
                current.restore(
                        fresh.getStatus(), fresh.getStage(), fresh.getManifest(), fresh.getResult(),
                        fresh.getFailureCode(), fresh.getError(), fresh.getRetryOfTurnId(), fresh.getVersion());
                if (fresh.isCancelRequested()) current.requestCancel();
            }
            return current;
        });
    }

    private TurnRecord readRecord(ResultSet rs) throws SQLException {
        TurnRequest request = read(rs.getString("request_json"), TurnRequest.class);
        TurnRecord record = new TurnRecord(
                rs.getString("turn_id"),
                rs.getString("trace_id"),
                request,
                rs.getTimestamp("accepted_at").toInstant(),
                rs.getTimestamp("deadline_at").toInstant());
        String manifestJson = rs.getString("manifest_json");
        String resultJson = rs.getString("result_json");
        HarnessManifest manifest = manifestJson == null ? null : read(manifestJson, HarnessManifest.class);
        ChatResponse result = resultJson == null ? null : read(resultJson, ChatResponse.class);
        record.restore(
                TurnStatus.fromWireValue(rs.getString("status")),
                TurnStage.valueOf(rs.getString("stage").toUpperCase(Locale.ROOT)),
                manifest,
                result,
                rs.getString("failure_code"),
                rs.getString("error"),
                rs.getString("retry_of_turn_id"),
                rs.getLong("version"));
        if (rs.getBoolean("cancel_requested")) record.requestCancel();
        return record;
    }

    private EventEnvelope readEvent(ResultSet rs) throws SQLException {
        return new EventEnvelope(
                rs.getString("protocol_version"),
                rs.getString("event_id"),
                rs.getBoolean("required"),
                rs.getString("required_extension"),
                rs.getString("event_type"),
                rs.getString("turn_id"),
                rs.getString("session_id"),
                rs.getLong("sequence"),
                com.meguri.core.adapter.domain.ReplayPolicy.valueOf(rs.getString("replay_policy")),
                rs.getTimestamp("created_at").toInstant(),
                read(rs.getString("data_json"), OBJECT_MAP),
                read(rs.getString("metadata_json"), EventMetadata.class));
    }

    private TurnRecord newRecord(TurnRequest request, Instant deadlineAt) {
        return new TurnRecord(newId("turn"), newId("trace"), request, deadlineAt);
    }

    private String payloadHash(TurnRequest request) {
        return digest(write(request).getBytes(StandardCharsets.UTF_8));
    }

    private String nullableJson(Object value) {
        return value == null ? null : write(value);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize Turn journal value", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize Turn journal value", error);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize Turn journal value", error);
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

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "duration");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException("duration must be positive");
        return value;
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record IdempotencyRow(String turnId, String payloadHash) { }

    private record CancellationState(boolean requested, long version) { }
}
