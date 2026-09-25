package com.meguri.core.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL authority for approvals, write-operation idempotency and audit. */
public final class JdbcCapabilityRuntimeStore implements
        ApprovalService, OperationStore, CapabilityAudit, CapabilityBindingStore {
    private static final String GLOBAL_TENANT = "*";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcCapabilityRuntimeStore(
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                new ClassPathResource("db/capability-runtime.sql"));
        schema.setContinueOnError(false);
        schema.execute(Objects.requireNonNull(
                jdbc.getDataSource(), "capability runtime data source"));
    }

    public void saveDefinition(CapabilityDescriptor descriptor) {
        jdbc.update("""
                INSERT INTO capability_definition (
                    capability_id, version, kind, owner_name,
                    descriptor, schema_fingerprint
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (capability_id, version) DO UPDATE
                SET descriptor = EXCLUDED.descriptor,
                    schema_fingerprint = EXCLUDED.schema_fingerprint
                WHERE capability_definition.descriptor = EXCLUDED.descriptor
                """,
                descriptor.id(),
                descriptor.version(),
                descriptor.kind().name(),
                descriptor.owner(),
                write(descriptor),
                fingerprint(descriptor));
        String stored = jdbc.queryForObject("""
                SELECT descriptor::text
                FROM capability_definition
                WHERE capability_id = ? AND version = ?
                """, String.class, descriptor.id(), descriptor.version());
        if (!jsonEquals(stored, write(descriptor))) {
            throw new IllegalArgumentException(
                    "capability version already has different metadata: "
                            + descriptor.id() + "@" + descriptor.version());
        }
    }

    @Override
    public Optional<CapabilityBindingStore.Binding> findBinding(
            String capabilityId) {
        return jdbc.query("""
                        SELECT capability_id, active_version, enabled,
                               draining, health, updated_at
                        FROM capability_binding
                        WHERE tenant_id = ? AND capability_id = ?
                        """,
                result -> result.next()
                        ? Optional.of(new CapabilityBindingStore.Binding(
                                result.getString("capability_id"),
                                result.getString("active_version"),
                                result.getBoolean("enabled"),
                                result.getBoolean("draining"),
                                CapabilityDescriptor.Health.valueOf(
                                        result.getString("health")),
                                result.getTimestamp("updated_at").toInstant()))
                        : Optional.empty(),
                GLOBAL_TENANT,
                CapabilityDescriptor.required(capabilityId, "capabilityId"));
    }

    @Override
    public void save(CapabilityBindingStore.Binding binding) {
        jdbc.update("""
                INSERT INTO capability_binding (
                    tenant_id, capability_id, active_version,
                    enabled, draining, health, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, capability_id) DO UPDATE
                SET active_version = EXCLUDED.active_version,
                    enabled = EXCLUDED.enabled,
                    draining = EXCLUDED.draining,
                    health = EXCLUDED.health,
                    updated_at = EXCLUDED.updated_at
                """,
                GLOBAL_TENANT,
                binding.capabilityId(),
                binding.version(),
                binding.enabled(),
                binding.draining(),
                binding.health().name(),
                Timestamp.from(binding.updatedAt()));
    }

    @Override
    public Claim claim(
            String tenantId,
            String userId,
            String capabilityId,
            String operationId,
            String idempotencyKey) {
        throw new IllegalStateException(
                "JDBC capability claims require the frozen proposal context");
    }

    @Override
    public Claim claim(
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            String snapshotId) {
        saveDefinition(descriptor);
        StoredOperation existing = existing(proposal);
        if (existing != null) return new Claim(existing.entry(), false);
        try {
            jdbc.update("""
                    INSERT INTO capability_execution (
                        execution_id, operation_id, tenant_id, user_id,
                        client_id, turn_id, trace_id, snapshot_id,
                        capability_id, capability_version, idempotency_key,
                        request_digest, status, started_at
                    ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', ?)
                    """,
                    UUID.randomUUID().toString(),
                    proposal.operationId(),
                    proposal.tenantId(),
                    proposal.userId(),
                    proposal.clientId(),
                    proposal.turnId(),
                    proposal.traceId(),
                    snapshotId,
                    descriptor.id(),
                    descriptor.version(),
                    proposal.idempotencyKey(),
                    CapabilityDigest.sha256(proposal.input()),
                    Timestamp.from(Instant.now()));
            return new Claim(new Entry(
                    proposal.operationId(),
                    proposal.idempotencyKey(),
                    State.STARTED,
                    null,
                    Instant.now(),
                    CapabilityDigest.sha256(proposal.input())), true);
        } catch (DataIntegrityViolationException concurrent) {
            StoredOperation winner = existing(proposal);
            if (winner != null) return new Claim(winner.entry(), false);
            throw concurrent;
        }
    }

    @Override
    public void complete(String operationId, CapabilityResult result) {
        Objects.requireNonNull(result, "result");
        int updated = jdbc.update("""
                UPDATE capability_execution
                SET status = ?, error_code = ?, result_data = ?::jsonb,
                    result_display = ?, result_source = ?::jsonb,
                    result_warnings = ?::jsonb, retryable = ?,
                    completed_at = ?
                WHERE operation_id = ? AND completed_at IS NULL
                """,
                result.status().name(),
                result.errorCode(),
                write(result.data()),
                result.display(),
                write(result.source()),
                write(result.warnings()),
                result.retryable(),
                Timestamp.from(Instant.now()),
                required(operationId, "operationId"));
        if (updated == 1) return;
        Entry current = find(operationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown operation"));
        if (!Objects.equals(current.result(), result)) {
            throw new IllegalStateException(
                    "operation is already completed with a different result");
        }
    }

    @Override
    public Optional<Entry> find(String operationId) {
        return findByOperation(required(operationId, "operationId"))
                .map(StoredOperation::entry);
    }

    @Override
    public Approval request(
            ToolProposal proposal, CapabilityDescriptor descriptor) {
        return request(proposal, descriptor, "unbound");
    }

    @Override
    public Approval request(
            ToolProposal proposal,
            CapabilityDescriptor descriptor,
            String snapshotId) {
        Approval approval = new Approval(
                UUID.randomUUID().toString(),
                proposal.operationId(),
                descriptor.id(),
                Decision.PENDING,
                null,
                Instant.now(),
                null,
                required(snapshotId, "snapshotId"),
                proposal.turnId(),
                proposal.traceId(),
                proposal.tenantId(),
                proposal.userId(),
                proposal.clientId(),
                descriptor.version(),
                proposal.idempotencyKey(),
                CapabilityDigest.sha256(proposal.input()));
        jdbc.update("""
                INSERT INTO capability_approval (
                    approval_id, operation_id, capability_id, capability_version,
                    snapshot_id, turn_id, trace_id, tenant_id, user_id, client_id,
                    idempotency_key, request_digest, decision, actor,
                    created_at, resolved_at
                ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                approval.approvalId(),
                approval.operationId(),
                approval.capabilityId(),
                approval.capabilityVersion(),
                approval.snapshotId(),
                approval.turnId(),
                approval.traceId(),
                approval.tenantId(),
                approval.userId(),
                approval.clientId(),
                approval.idempotencyKey(),
                approval.requestDigest(),
                approval.decision().name(),
                approval.actor(),
                Timestamp.from(approval.createdAt()),
                null);
        return approval;
    }

    @Override
    public Approval resolve(
            String approvalId, Decision decision, String actor) {
        if (decision == null || decision == Decision.PENDING) {
            throw new IllegalArgumentException(
                    "approval resolution must be terminal");
        }
        String safeActor = CapabilityDescriptor.required(actor, "actor");
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE capability_approval
                SET decision = ?, actor = ?, resolved_at = ?
                WHERE approval_id = ?::uuid AND decision = 'PENDING'
                """,
                decision.name(),
                safeActor,
                Timestamp.from(now),
                required(approvalId, "approvalId"));
        Approval current = findApproval(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("unknown approval"));
        if (updated == 0 && current.decision() != decision) {
            throw new IllegalStateException("approval is already resolved");
        }
        return current;
    }

    @Override
    public Optional<Approval> findApproval(String approvalId) {
        return jdbc.query("""
                        SELECT approval_id::text, operation_id, capability_id,
                               capability_version, snapshot_id, turn_id, trace_id,
                               tenant_id, user_id, client_id, idempotency_key,
                               request_digest, decision, actor, created_at, resolved_at
                        FROM capability_approval
                        WHERE approval_id = ?::uuid
                        """,
                result -> result.next()
                        ? Optional.of(approval(result))
                        : Optional.empty(),
                required(approvalId, "approvalId"));
    }

    @Override
    public List<Approval> approvals() {
        return jdbc.query("""
                SELECT approval_id::text, operation_id, capability_id,
                       capability_version, snapshot_id, turn_id, trace_id,
                       tenant_id, user_id, client_id, idempotency_key,
                       request_digest, decision, actor, created_at, resolved_at
                FROM capability_approval
                ORDER BY created_at, approval_id
                """, (result, row) -> approval(result));
    }

    @Override
    public void record(Event event) {
        Objects.requireNonNull(event, "event");
        jdbc.update("""
                INSERT INTO capability_audit (
                    event_id, occurred_at, turn_id, trace_id, snapshot_id,
                    tenant_id, user_id, client_id, capability_id,
                    capability_version, operation_id, idempotency_key,
                    request_digest, result_digest, approval_id,
                    approval_decision, phase, attempt, status,
                    error_code, duration_ms, external_result, retryable
                ) VALUES (
                    ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(),
                Timestamp.from(event.occurredAt()),
                event.turnId(),
                event.traceId(),
                event.snapshotId(),
                event.tenantId(),
                event.userId(),
                event.clientId(),
                event.capabilityId(),
                event.capabilityVersion(),
                event.operationId(),
                event.idempotencyKey(),
                event.requestDigest(),
                event.resultDigest(),
                event.approvalId(),
                event.approvalDecision() == null
                        ? null : event.approvalDecision().name(),
                event.phase(),
                event.attempt(),
                event.status(),
                event.errorCode(),
                event.durationMs(),
                event.externalResult(),
                event.retryable());
    }

    @Override
    public List<Event> events() {
        return jdbc.query("""
                SELECT event_id::text, occurred_at, turn_id, trace_id,
                       snapshot_id, tenant_id, user_id, client_id,
                       capability_id, capability_version, operation_id,
                       idempotency_key, request_digest, result_digest,
                       approval_id::text,
                       approval_decision, phase, attempt, status,
                       error_code, duration_ms, external_result, retryable
                FROM capability_audit
                ORDER BY occurred_at, event_id
                """, (result, row) -> audit(result));
    }

    private StoredOperation existing(ToolProposal proposal) {
        Optional<StoredOperation> byOperation =
                findByOperation(proposal.operationId());
        if (byOperation.isPresent()) {
            StoredOperation value = byOperation.orElseThrow();
            if (!value.sameScope(proposal)) {
                throw new IllegalArgumentException(
                        "operation_id already exists in another scope");
            }
            return value;
        }
        if (proposal.idempotencyKey() == null) return null;
        return jdbc.query("""
                        SELECT operation_id, idempotency_key, status,
                               result_data::text, result_display,
                               result_source::text, result_warnings::text,
                               error_code, retryable, completed_at, started_at,
                               request_digest,
                               tenant_id, user_id, capability_id
                        FROM capability_execution
                        WHERE tenant_id = ? AND user_id = ?
                          AND capability_id = ? AND idempotency_key = ?
                        """,
                result -> result.next()
                        ? new StoredOperation(
                                operation(result),
                                result.getString("tenant_id"),
                                result.getString("user_id"),
                                result.getString("capability_id"))
                        : null,
                proposal.tenantId(),
                proposal.userId(),
                proposal.capabilityId(),
                proposal.idempotencyKey());
    }

    private Optional<StoredOperation> findByOperation(String operationId) {
        return jdbc.query("""
                        SELECT operation_id, idempotency_key, status,
                               result_data::text, result_display,
                               result_source::text, result_warnings::text,
                               error_code, retryable, completed_at, started_at,
                               request_digest,
                               tenant_id, user_id, capability_id
                        FROM capability_execution
                        WHERE operation_id = ?
                        """,
                result -> result.next()
                        ? Optional.of(new StoredOperation(
                                operation(result),
                                result.getString("tenant_id"),
                                result.getString("user_id"),
                                result.getString("capability_id")))
                        : Optional.empty(),
                operationId);
    }

    private Entry operation(ResultSet result) throws SQLException {
        Timestamp completedAt = result.getTimestamp("completed_at");
        String status = result.getString("status");
        CapabilityResult capabilityResult = completedAt == null
                ? null
                : new CapabilityResult(
                        CapabilityResult.Status.valueOf(status),
                        result.getString("error_code"),
                        read(result.getString("result_data"), OBJECT_MAP, Map.of()),
                        result.getString("result_display"),
                        read(
                                result.getString("result_source"),
                                CapabilityResult.Source.class,
                                null),
                        read(result.getString("result_warnings"), STRING_LIST, List.of()),
                        result.getBoolean("retryable"));
        State state = completedAt == null
                ? State.STARTED
                : capabilityResult.status() == CapabilityResult.Status.UNKNOWN_OUTCOME
                        ? State.UNKNOWN : State.COMPLETED;
        Instant updatedAt = completedAt == null
                ? result.getTimestamp("started_at").toInstant()
                : completedAt.toInstant();
        return new Entry(
                result.getString("operation_id"),
                result.getString("idempotency_key"),
                state,
                capabilityResult,
                updatedAt,
                result.getString("request_digest"));
    }

    private static Approval approval(ResultSet result) throws SQLException {
        Timestamp resolvedAt = result.getTimestamp("resolved_at");
        return new Approval(
                result.getString("approval_id"),
                result.getString("operation_id"),
                result.getString("capability_id"),
                Decision.valueOf(result.getString("decision")),
                result.getString("actor"),
                result.getTimestamp("created_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                result.getString("snapshot_id"),
                result.getString("turn_id"),
                result.getString("trace_id"),
                result.getString("tenant_id"),
                result.getString("user_id"),
                result.getString("client_id"),
                result.getString("capability_version"),
                result.getString("idempotency_key"),
                result.getString("request_digest"));
    }

    private static Event audit(ResultSet result) throws SQLException {
        String approvalDecision = result.getString("approval_decision");
        return new Event(
                result.getString("event_id"),
                result.getTimestamp("occurred_at").toInstant(),
                result.getString("turn_id"),
                result.getString("trace_id"),
                result.getString("snapshot_id"),
                result.getString("tenant_id"),
                result.getString("user_id"),
                result.getString("client_id"),
                result.getString("capability_id"),
                result.getString("capability_version"),
                result.getString("operation_id"),
                result.getString("idempotency_key"),
                result.getString("request_digest"),
                result.getString("result_digest"),
                result.getString("approval_id"),
                approvalDecision == null
                        ? null : Decision.valueOf(approvalDecision),
                result.getString("phase"),
                result.getInt("attempt"),
                result.getString("status"),
                result.getString("error_code"),
                result.getLong("duration_ms"),
                result.getBoolean("external_result"),
                result.getBoolean("retryable"));
    }

    private boolean jsonEquals(String left, String right) {
        try {
            return mapper.readTree(left).equals(mapper.readTree(right));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "invalid persisted capability JSON", error);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "failed to serialize capability runtime value", error);
        }
    }

    private <T> T read(
            String value,
            TypeReference<T> type,
            T fallback) {
        if (value == null) return fallback;
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "failed to deserialize capability runtime value", error);
        }
    }

    private <T> T read(String value, Class<T> type, T fallback) {
        if (value == null) return fallback;
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "failed to deserialize capability runtime value", error);
        }
    }

    private static String fingerprint(CapabilityDescriptor descriptor) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            descriptor.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String field) {
        return CapabilityDescriptor.required(value, field);
    }

    private record StoredOperation(
            Entry entry,
            String tenantId,
            String userId,
            String capabilityId) {
        private boolean sameScope(ToolProposal proposal) {
            return tenantId.equals(proposal.tenantId())
                    && userId.equals(proposal.userId())
                    && capabilityId.equals(proposal.capabilityId());
        }
    }
}
