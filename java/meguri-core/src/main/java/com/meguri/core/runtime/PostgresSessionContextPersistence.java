package com.meguri.core.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.context.ContextBundle;
import com.meguri.core.context.ContextRuntimePersistence;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL projection for complete Companion Context graph snapshots. */
public final class PostgresSessionContextPersistence
        implements SessionContextPersistence, ContextRuntimePersistence {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresSessionContextPersistence(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (jdbc.getDataSource() == null) throw new IllegalStateException("context persistence requires a datasource");
        new ResourceDatabasePopulator(new ClassPathResource("db/turn-runtime.sql"))
                .execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource("db/context-runtime.sql"))
                .execute(jdbc.getDataSource());
    }

    @Override
    public List<SessionContextStore.GraphSnapshot> loadAll() {
        return jdbc.query("""
                        SELECT snapshot_json
                        FROM session_context_graph
                        ORDER BY user_id, client_id, session_id
                        """,
                (rs, rowNum) -> read(rs.getString("snapshot_json")));
    }

    @Override
    public Optional<SessionContextStore.GraphSnapshot> load(
            String userId, String clientId, String sessionId) {
        return jdbc.query("""
                        SELECT snapshot_json FROM session_context_graph
                        WHERE user_id = ? AND client_id = ? AND session_id = ?
                        """, (rs, rowNum) -> read(rs.getString("snapshot_json")),
                userId, clientId, sessionId).stream().findFirst();
    }

    @Override
    public void save(SessionContextStore.GraphSnapshot snapshot) {
        int updated = jdbc.update("""
                INSERT INTO session_context_graph (
                    user_id, client_id, session_id, revision, snapshot_json, updated_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, client_id, session_id) DO UPDATE
                SET revision = EXCLUDED.revision,
                    snapshot_json = EXCLUDED.snapshot_json,
                    updated_at = CURRENT_TIMESTAMP
                WHERE session_context_graph.revision = EXCLUDED.revision - 1
                """,
                snapshot.userId(), snapshot.clientId(), snapshot.sessionId(),
                snapshot.revision(), write(snapshot));
        if (updated != 1) {
            throw new IllegalStateException(
                    "session context revision conflict for " + snapshot.sessionId());
        }
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM session_context_graph");
    }

    @Override
    public void saveTrace(ContextBuildTrace trace) {
        jdbc.update("""
                INSERT INTO context_build_trace (
                    trace_id, conversation_id, graph_revision, request_digest, bundle_json, created_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (trace_id) DO NOTHING
                """, trace.traceId(), trace.conversationId(), trace.graphRevision(), trace.requestDigest(),
                writeValue(trace.bundle()), Timestamp.from(trace.createdAt()));
    }

    @Override
    public Optional<ContextBuildTrace> findTrace(String traceId) {
        return jdbc.query("""
                        SELECT trace_id, conversation_id, graph_revision, request_digest, bundle_json, created_at
                        FROM context_build_trace WHERE trace_id = ?
                        """, (rs, rowNum) -> new ContextBuildTrace(
                        rs.getString("trace_id"), rs.getString("conversation_id"),
                        rs.getLong("graph_revision"), rs.getString("request_digest"),
                        readValue(rs.getString("bundle_json"), ContextBundle.class),
                        rs.getTimestamp("created_at").toInstant()), traceId)
                .stream().findFirst();
    }

    @Override
    public TopicSegment saveTopicSegment(TopicSegment segment) {
        jdbc.update("""
                INSERT INTO context_topic_segment (
                    segment_id, conversation_id, label, status, confidence, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (segment_id) DO UPDATE SET
                    label = EXCLUDED.label, status = EXCLUDED.status,
                    confidence = EXCLUDED.confidence, reason = EXCLUDED.reason
                """, segment.segmentId(), segment.conversationId(), segment.label(), segment.status().name(),
                segment.confidence(), segment.reason(), Timestamp.from(segment.createdAt()));
        return segment;
    }

    @Override
    public List<TopicSegment> findTopicSegments(String conversationId) {
        return jdbc.query("""
                        SELECT segment_id, conversation_id, label, status, confidence, reason, created_at
                        FROM context_topic_segment WHERE conversation_id = ? ORDER BY created_at
                        """, (rs, rowNum) -> new TopicSegment(
                        rs.getString("segment_id"), rs.getString("conversation_id"), rs.getString("label"),
                        TopicStatus.valueOf(rs.getString("status")), rs.getDouble("confidence"),
                        rs.getString("reason"), rs.getTimestamp("created_at").toInstant()), conversationId);
    }

    @Override
    public PrecompressionJob enqueuePrecompression(PrecompressionJob job) {
        jdbc.update("""
                INSERT INTO context_precompression_job (
                    job_id, idempotency_key, user_id, client_id, conversation_id,
                    graph_revision, source_message_ids, model_id, status,
                    attempts, available_at, lease_until, claim_owner, claim_token,
                    summary_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, job.jobId(), job.idempotencyKey(), job.userId(), job.clientId(),
                job.conversationId(), job.graphRevision(), writeStringList(job.sourceMessageIds()),
                job.modelId(), job.status().name(), job.attempts(),
                Timestamp.from(job.availableAt()), timestamp(job.leaseUntil()),
                null, null, job.summaryId(), Timestamp.from(job.createdAt()));
        return findJobByKey(job.idempotencyKey()).orElseThrow();
    }

    @Override
    public List<PrecompressionJob> recoverablePrecompressionJobs(Instant now) {
        return jdbc.query("""
                        SELECT * FROM context_precompression_job
                        WHERE (status = 'PENDING' AND available_at <= ?)
                           OR (status = 'RUNNING' AND lease_until <= ?)
                        ORDER BY available_at, created_at
                        """, (rs, rowNum) -> readJob(rs), Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public Optional<PrecompressionJob> claimPrecompression(
            String jobId, String ownerId, String claimToken, Instant leaseUntil) {
        List<PrecompressionJob> claimed = jdbc.query("""
                        UPDATE context_precompression_job
                        SET status = 'RUNNING', attempts = attempts + 1, lease_until = ?,
                            claim_owner = ?, claim_token = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE job_id = ?
                          AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_until <= CURRENT_TIMESTAMP))
                        RETURNING *
                        """, (rs, rowNum) -> readJob(rs), Timestamp.from(leaseUntil),
                ownerId, claimToken, jobId);
        return claimed.stream().findFirst();
    }

    @Override
    public boolean heartbeatPrecompression(
            String jobId, String ownerId, String claimToken, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE context_precompression_job
                SET lease_until = ?, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND status = 'RUNNING'
                  AND claim_owner = ? AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, Timestamp.from(leaseUntil), jobId, ownerId, claimToken) == 1;
    }

    @Override
    public boolean completePrecompression(
            String jobId, String ownerId, String claimToken, String summaryId) {
        return jdbc.update("""
                UPDATE context_precompression_job
                SET status = 'COMPLETED', summary_id = ?, lease_until = NULL,
                    claim_owner = NULL, claim_token = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND status = 'RUNNING'
                  AND claim_owner = ? AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, summaryId, jobId, ownerId, claimToken) == 1;
    }

    @Override
    public boolean retryPrecompression(
            String jobId, String ownerId, String claimToken,
            Instant availableAt, boolean terminal) {
        return jdbc.update("""
                UPDATE context_precompression_job
                SET status = ?, available_at = ?, lease_until = NULL,
                    claim_owner = NULL, claim_token = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND status = 'RUNNING'
                  AND claim_owner = ? AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, terminal ? JobStatus.FAILED.name() : JobStatus.PENDING.name(),
                Timestamp.from(availableAt), jobId, ownerId, claimToken) == 1;
    }

    private Optional<PrecompressionJob> findJobByKey(String idempotencyKey) {
        return jdbc.query("SELECT * FROM context_precompression_job WHERE idempotency_key = ?",
                (rs, rowNum) -> readJob(rs), idempotencyKey).stream().findFirst();
    }

    private PrecompressionJob readJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lease = rs.getTimestamp("lease_until");
        return new PrecompressionJob(
                rs.getString("job_id"), rs.getString("idempotency_key"),
                rs.getString("user_id"), rs.getString("client_id"),
                rs.getString("conversation_id"), rs.getLong("graph_revision"),
                readStringList(rs.getString("source_message_ids")), rs.getString("model_id"),
                JobStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getTimestamp("available_at").toInstant(), lease == null ? null : lease.toInstant(),
                rs.getString("summary_id"), rs.getTimestamp("created_at").toInstant());
    }

    private String writeStringList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize precompression sources", error);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize precompression sources", error);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String write(SessionContextStore.GraphSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize session context graph", error);
        }
    }

    private SessionContextStore.GraphSnapshot read(String value) {
        try {
            return objectMapper.readValue(value, SessionContextStore.GraphSnapshot.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize session context graph", error);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize context runtime value", error);
        }
    }

    private <T> T readValue(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize context runtime value", error);
        }
    }
}
