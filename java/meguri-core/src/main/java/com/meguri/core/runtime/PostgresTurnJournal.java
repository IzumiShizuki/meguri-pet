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

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final String recoveryBuildId;
    private final Map<String, TurnRecord> turns = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<EventEnvelope>> events = new ConcurrentHashMap<>();

    public PostgresTurnJournal(JdbcTemplate jdbc, ObjectMapper objectMapper,
                               TransactionTemplate transactions, String recoveryBuildId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.recoveryBuildId = required(recoveryBuildId, "recoveryBuildId");
        initializeSchema();
        loadState();
        recoverInterruptedTurns();
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
        return turns.get(turnId);
    }

    @Override
    public Map<String, TurnRecord> turns() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(turns));
    }

    @Override
    public List<EventEnvelope> events(String sessionId) {
        return jdbc.query("""
                        SELECT event_id, protocol_version, required, event_type, turn_id,
                               session_id, sequence, data_json, metadata_json
                        FROM turn_event
                        WHERE session_id = ?
                        ORDER BY sequence
                        """,
                (rs, rowNum) -> readEvent(rs), sessionId);
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
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(metadata, "metadata");
        EventEnvelope event = transactions.execute(status -> appendInTransaction(
                record, type, data == null ? Map.of() : data, metadata));
        if (event == null) throw new IllegalStateException("event transaction returned no event");
        events.computeIfAbsent(event.getSessionId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
        return event;
    }

    @Override
    public void persist(TurnRecord record) {
        Objects.requireNonNull(record, "record");
        transactions.executeWithoutResult(status -> persistInTransaction(record));
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
        jdbc.query("""
                        SELECT turn_id, trace_id, request_json, status, stage, accepted_at,
                               deadline_at, manifest_json, result_json, error
                        FROM turn_runtime
                        ORDER BY accepted_at, turn_id
                        """,
                (ResultSet rs) -> {
                    TurnRecord record = readRecord(rs);
                    turns.put(record.getTurnId(), record);
                });

        events.clear();
        jdbc.query("""
                        SELECT event_id, protocol_version, required, event_type, turn_id,
                               session_id, sequence, data_json, metadata_json
                        FROM turn_event
                        ORDER BY session_id, sequence
                        """,
                (ResultSet rs) -> {
                    EventEnvelope event = readEvent(rs);
                    events.computeIfAbsent(event.getSessionId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
                });
    }

    private void recoverInterruptedTurns() {
        List<TurnRecord> interrupted = turns.values().stream()
                .filter(record -> !record.isTerminal())
                .toList();
        for (TurnRecord record : interrupted) {
            record.tryFail("turn interrupted by meguri-core restart");
            EventEnvelope recovered = transactions.execute(status -> appendInTransaction(
                    record,
                    "turn.failed",
                    Map.of("error", record.getError(), "recovered_after_restart", true),
                    new EventMetadata(record.getTraceId(), "meguri-core", Instant.now(), recoveryBuildId)));
            if (recovered != null) {
                events.computeIfAbsent(recovered.getSessionId(), ignored -> new CopyOnWriteArrayList<>()).add(recovered);
            }
            record.completeDone();
        }
    }

    private EventEnvelope appendInTransaction(TurnRecord record, String type,
                                              Map<String, Object> data, EventMetadata metadata) {
        persistInTransaction(record);
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
                type,
                record.getTurnId(),
                sessionId,
                next.longValue(),
                data,
                metadata);
        jdbc.update("""
                INSERT INTO turn_event (
                    event_id, turn_id, session_id, sequence, protocol_version,
                    required, event_type, data_json, metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """,
                event.getEventId(), event.getTurnId(), event.getSessionId(), event.getSequence(),
                event.getProtocolVersion(), event.isRequired(), event.getType(), write(event.getData()),
                write(event.getMetadata()), Timestamp.from(event.getMetadata().getCreatedAt()));
        jdbc.update("""
                INSERT INTO turn_outbox (event_id, aggregate_id, session_id, payload_json)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """, event.getEventId(), event.getTurnId(), event.getSessionId(), write(event));
        return event;
    }

    private void persistInTransaction(TurnRecord record) {
        int updated = jdbc.update("""
                UPDATE turn_runtime
                SET status = ?, stage = ?, manifest_json = CAST(? AS jsonb),
                    result_json = CAST(? AS jsonb), error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE turn_id = ?
                """,
                record.statusValue(), record.getStage().wireValue(), nullableJson(record.getManifest()),
                nullableJson(record.getResult()), record.getError(), record.getTurnId());
        if (updated != 1) throw new IllegalStateException("turn is not present in PostgreSQL: " + record.getTurnId());
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
                    payload_hash, request_json, status, stage, accepted_at, deadline_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """ + conflict,
                record.getTurnId(), record.getTraceId(), request.getUserId(), request.getClientId(),
                request.getSessionId(), idempotencyKey, payloadHash, write(request),
                record.statusValue(), record.getStage().wireValue(), Timestamp.from(record.getAcceptedAt()),
                Timestamp.from(record.getDeadlineAt()));
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
        TurnRecord record = turns.get(existing.turnId());
        if (record == null) {
            record = jdbc.queryForObject("""
                            SELECT turn_id, trace_id, request_json, status, stage, accepted_at,
                                   deadline_at, manifest_json, result_json, error
                            FROM turn_runtime WHERE turn_id = ?
                            """, (rs, rowNum) -> readRecord(rs), existing.turnId());
            if (record != null) turns.put(record.getTurnId(), record);
        }
        if (record == null) throw new IllegalStateException("idempotent turn row disappeared");
        return new Acceptance(record, false);
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
                rs.getString("error"));
        return record;
    }

    private EventEnvelope readEvent(ResultSet rs) throws SQLException {
        return new EventEnvelope(
                rs.getString("protocol_version"),
                rs.getString("event_id"),
                rs.getBoolean("required"),
                rs.getString("event_type"),
                rs.getString("turn_id"),
                rs.getString("session_id"),
                rs.getLong("sequence"),
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

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record IdempotencyRow(String turnId, String payloadHash) { }
}
