package com.meguri.core.memory.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL store; claims are atomic and safe across worker processes. */
public final class JdbcPostReplyMemoryJobStore implements PostReplyMemoryJobStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPostReplyMemoryJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public EnqueueResult enqueue(PostReplyMemoryJob job) {
        int inserted = jdbc.update("""
                INSERT INTO post_reply_memory_job (
                    job_id, turn_id, response_digest, trace_id, request_json, response_json,
                    cancellation_policy, cancelled_after_reply, status, attempts, available_at,
                    owner_id, lease_until, last_error, created_at, updated_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (turn_id, response_digest) DO NOTHING
                """, job.jobId(), job.turnId(), job.responseDigest(), job.traceId(),
                write(job.request()), write(job.response()), job.cancellationPolicy().name(),
                job.cancelledAfterReply(), job.status().name(), job.attempts(), timestamp(job.availableAt()),
                job.ownerId(), timestamp(job.leaseUntil()), job.lastError(), timestamp(job.createdAt()),
                timestamp(job.updatedAt()));
        if (inserted == 1) return new EnqueueResult(job, true);
        PostReplyMemoryJob existing = jdbc.queryForObject("""
                SELECT * FROM post_reply_memory_job WHERE turn_id = ? AND response_digest = ?
                """, this::read, job.turnId(), job.responseDigest());
        if (existing == null) throw new IllegalStateException("idempotent memory job disappeared");
        return new EnqueueResult(existing, false);
    }

    @Override
    public List<PostReplyMemoryJob> claim(String ownerId, int limit, Duration lease, Instant now) {
        requireClaim(ownerId, limit, lease, now);
        return jdbc.query("""
                WITH candidates AS (
                    SELECT job_id FROM post_reply_memory_job
                    WHERE (status = 'PENDING' AND available_at <= ?)
                       OR (status = 'RUNNING' AND lease_until <= ?)
                    ORDER BY available_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE post_reply_memory_job job
                SET status = 'RUNNING', owner_id = ?, lease_until = ?, attempts = attempts + 1,
                    updated_at = ?
                FROM candidates
                WHERE job.job_id = candidates.job_id
                RETURNING job.*
                """, this::read, timestamp(now), timestamp(now), limit, ownerId,
                timestamp(now.plus(lease)), timestamp(now));
    }

    @Override
    public boolean heartbeat(String jobId, String ownerId, Duration lease, Instant now) {
        return jdbc.update("""
                UPDATE post_reply_memory_job SET lease_until = ?, updated_at = ?
                WHERE job_id = ? AND status = 'RUNNING' AND owner_id = ? AND lease_until > ?
                """, timestamp(now.plus(lease)), timestamp(now), jobId, ownerId, timestamp(now)) == 1;
    }

    @Override
    public boolean acknowledge(String jobId, String ownerId,
                               PostReplyMemoryJob.Status terminalStatus, Instant now) {
        if (terminalStatus != PostReplyMemoryJob.Status.SUCCEEDED
                && terminalStatus != PostReplyMemoryJob.Status.SKIPPED) {
            throw new IllegalArgumentException("acknowledgement status must be terminal success or skipped");
        }
        return jdbc.update("""
                UPDATE post_reply_memory_job
                SET status = ?, owner_id = NULL, lease_until = NULL, updated_at = ?
                WHERE job_id = ? AND status = 'RUNNING' AND owner_id = ? AND lease_until > ?
                """, terminalStatus.name(), timestamp(now), jobId, ownerId, timestamp(now)) == 1;
    }

    @Override
    public boolean retry(String jobId, String ownerId, String error, Instant availableAt,
                         boolean deadLetter, Instant now) {
        return jdbc.update("""
                UPDATE post_reply_memory_job
                SET status = ?, available_at = ?, owner_id = NULL, lease_until = NULL,
                    last_error = ?, updated_at = ?
                WHERE job_id = ? AND status = 'RUNNING' AND owner_id = ? AND lease_until > ?
                """, deadLetter ? "DEAD_LETTER" : "PENDING", timestamp(availableAt), safe(error),
                timestamp(now), jobId, ownerId, timestamp(now)) == 1;
    }

    @Override
    public Optional<PostReplyMemoryJob> find(String jobId) {
        List<PostReplyMemoryJob> values = jdbc.query(
                "SELECT * FROM post_reply_memory_job WHERE job_id = ?", this::read, jobId);
        return values.stream().findFirst();
    }

    private PostReplyMemoryJob read(ResultSet rs, int row) throws SQLException {
        return new PostReplyMemoryJob(rs.getString("job_id"), rs.getString("turn_id"),
                rs.getString("response_digest"), rs.getString("trace_id"),
                readRequest(rs.getString("request_json")),
                read(rs.getString("response_json"), LlmResponse.class),
                PostReplyMemoryJob.CancellationPolicy.valueOf(rs.getString("cancellation_policy")),
                rs.getBoolean("cancelled_after_reply"),
                PostReplyMemoryJob.Status.valueOf(rs.getString("status")), rs.getInt("attempts"),
                instant(rs, "available_at"), rs.getString("owner_id"), instant(rs, "lease_until"),
                rs.getString("last_error"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("invalid job payload", error); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("invalid persisted job payload", error); }
    }

    private TurnRequest readRequest(String value) {
        try {
            var node = mapper.readTree(value);
            TurnRequest request = mapper.treeToValue(node, TurnRequest.class);
            String tenantId = node.path("tenant_id").asText("meguri-local");
            request = request.withTenantId(tenantId);
            String platformId = node.path("platform_id").asText("");
            String actorId = node.path("platform_actor_id").asText("");
            String instanceId = node.path("client_instance_id").asText("");
            if (!platformId.isBlank() && !actorId.isBlank() && !instanceId.isBlank()) {
                request = request.withAdapterIdentity(platformId, actorId, instanceId);
            }
            return request;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid persisted turn request", error);
        }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static String safe(String value) {
        if (value == null || value.isBlank()) return "memory candidate write failed";
        return value.substring(0, Math.min(2000, value.length()));
    }
    private static void requireClaim(String ownerId, int limit, Duration lease, Instant now) {
        if (ownerId == null || ownerId.isBlank() || limit < 1 || lease == null
                || lease.isZero() || lease.isNegative() || now == null) {
            throw new IllegalArgumentException("valid owner, limit, lease and now are required");
        }
    }
}
