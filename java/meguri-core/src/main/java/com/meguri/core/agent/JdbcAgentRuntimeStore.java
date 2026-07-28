package com.meguri.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JdbcTemplate adapter for all three agent-runtime persistence ports. Updates
 * use version predicates so competing resumptions fail closed.
 */
public final class JdbcAgentRuntimeStore implements
        AgentExecutionStores.RuntimeStore {

    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAgentRuntimeStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public SkillExecution create(SkillExecution value) {
        jdbc.update("""
                INSERT INTO skill_execution (
                    execution_id, turn_id, skill_id, capability_snapshot_version,
                    status, deadline_at, error, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.executionId(), value.turnId(), value.skillId(),
                value.capabilitySnapshotVersion(), value.status().name(),
                timestamp(value.deadline()), value.error(), value.version(),
                timestamp(value.createdAt()), timestamp(value.updatedAt()));
        return value;
    }

    @Override
    public Optional<SkillExecution> findSkill(String executionId) {
        return first(jdbc.query("SELECT * FROM skill_execution WHERE execution_id = ?",
                this::readSkill, executionId));
    }

    @Override
    public SkillExecution save(SkillExecution value, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE skill_execution
                SET status = ?, error = ?, version = ?, updated_at = ?
                WHERE execution_id = ? AND version = ?
                """, value.status().name(), value.error(), value.version(), timestamp(value.updatedAt()),
                value.executionId(), expectedVersion);
        requireUpdated(updated, "skill execution", value.executionId());
        return value;
    }

    @Override
    public StepExecution create(StepExecution value) {
        jdbc.update("""
                INSERT INTO skill_step_execution (
                    step_execution_id, execution_id, step_key, execution_domain, status,
                    result_json, error, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """, value.stepExecutionId(), value.executionId(), value.stepKey(), value.domain().name(),
                value.status().name(), value.resultJson(), value.error(), value.version(),
                timestamp(value.createdAt()), timestamp(value.updatedAt()));
        return value;
    }

    @Override
    public Optional<StepExecution> findStep(String stepExecutionId) {
        return first(jdbc.query("SELECT * FROM skill_step_execution WHERE step_execution_id = ?",
                this::readStep, stepExecutionId));
    }

    @Override
    public List<StepExecution> findSteps(String executionId) {
        return jdbc.query("""
                SELECT * FROM skill_step_execution
                WHERE execution_id = ? ORDER BY created_at, step_execution_id
                """, this::readStep, executionId);
    }

    @Override
    public StepExecution save(StepExecution value, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE skill_step_execution
                SET status = ?, result_json = CAST(? AS jsonb), error = ?, version = ?, updated_at = ?
                WHERE step_execution_id = ? AND version = ?
                """, value.status().name(), value.resultJson(), value.error(), value.version(),
                timestamp(value.updatedAt()), value.stepExecutionId(), expectedVersion);
        requireUpdated(updated, "step execution", value.stepExecutionId());
        return value;
    }

    @Override
    public AgentExecutionStores.CreateResult createIdempotent(AgentTask value) {
        int inserted = jdbc.update("""
                INSERT INTO agent_task (
                    task_id, execution_id, step_execution_id, parent_task_id, resource_id,
                    remote_task_id, tenant_id, user_id, idempotency_key, payload_hash,
                    status, context_json, result_json, error, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                    CAST(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, idempotency_key) DO NOTHING
                """, value.taskId(), value.executionId(), value.stepExecutionId(), value.parentTaskId(),
                value.resourceId(), value.remoteTaskId(), value.tenantId(), value.userId(),
                value.idempotencyKey(), value.payloadHash(), value.status().name(),
                writeContext(value.context()), value.resultJson(), value.error(), value.version(),
                timestamp(value.createdAt()), timestamp(value.updatedAt()));
        if (inserted == 1) return new AgentExecutionStores.CreateResult(value, true);
        AgentTask existing = jdbc.queryForObject("""
                SELECT * FROM agent_task
                WHERE tenant_id = ? AND user_id = ? AND idempotency_key = ?
                """, this::readTask, value.tenantId(), value.userId(), value.idempotencyKey());
        if (existing == null) throw new IllegalStateException("idempotent agent task disappeared");
        if (!existing.payloadHash().equals(value.payloadHash())) {
            throw new AgentPolicyException("idempotency key reused with a different proposal");
        }
        return new AgentExecutionStores.CreateResult(existing, false);
    }

    @Override
    public Optional<AgentTask> findTask(String taskId) {
        return first(jdbc.query("SELECT * FROM agent_task WHERE task_id = ?", this::readTask, taskId));
    }

    @Override
    public Optional<AgentTask> findIdempotent(String tenantId, String userId, String idempotencyKey) {
        return first(jdbc.query("""
                SELECT * FROM agent_task
                WHERE tenant_id = ? AND user_id = ? AND idempotency_key = ?
                """, this::readTask, tenantId, userId, idempotencyKey));
    }

    @Override
    public Optional<AgentTask> findByRemoteTaskId(String remoteTaskId) {
        return first(jdbc.query("SELECT * FROM agent_task WHERE remote_task_id = ?", this::readTask, remoteTaskId));
    }

    @Override
    public List<AgentTask> findChildren(String parentTaskId) {
        if (parentTaskId == null) {
            return jdbc.query("SELECT * FROM agent_task WHERE parent_task_id IS NULL", this::readTask);
        }
        return jdbc.query("SELECT * FROM agent_task WHERE parent_task_id = ?", this::readTask, parentTaskId);
    }

    @Override
    public List<AgentTask> findResumable() {
        return jdbc.query("""
                SELECT * FROM agent_task
                WHERE status = 'WAITING_EXTERNAL' AND remote_task_id IS NOT NULL
                ORDER BY updated_at, task_id
                """, this::readTask);
    }

    @Override
    public AgentTask save(AgentTask value, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE agent_task
                SET remote_task_id = ?, status = ?, result_json = CAST(? AS jsonb), error = ?,
                    version = ?, updated_at = ?
                WHERE task_id = ? AND version = ?
                """, value.remoteTaskId(), value.status().name(), value.resultJson(), value.error(),
                value.version(), timestamp(value.updatedAt()), value.taskId(), expectedVersion);
        requireUpdated(updated, "agent task", value.taskId());
        return value;
    }

    private SkillExecution readSkill(ResultSet rs, int row) throws SQLException {
        return new SkillExecution(
                rs.getString("execution_id"), rs.getString("turn_id"), rs.getString("skill_id"),
                rs.getString("capability_snapshot_version"),
                AgentRuntimeState.SkillStatus.valueOf(rs.getString("status")),
                instant(rs, "deadline_at"), rs.getString("error"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private StepExecution readStep(ResultSet rs, int row) throws SQLException {
        return new StepExecution(
                rs.getString("step_execution_id"), rs.getString("execution_id"), rs.getString("step_key"),
                ExecutionDomain.valueOf(rs.getString("execution_domain")),
                AgentRuntimeState.StepStatus.valueOf(rs.getString("status")),
                rs.getString("result_json"), rs.getString("error"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private AgentTask readTask(ResultSet rs, int row) throws SQLException {
        return new AgentTask(
                rs.getString("task_id"), rs.getString("execution_id"), rs.getString("step_execution_id"),
                rs.getString("parent_task_id"), rs.getString("resource_id"), rs.getString("remote_task_id"),
                rs.getString("tenant_id"), rs.getString("user_id"), rs.getString("idempotency_key"),
                rs.getString("payload_hash"), AgentRuntimeState.AgentStatus.valueOf(rs.getString("status")),
                readContext(rs.getString("context_json")), rs.getString("result_json"), rs.getString("error"),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private String writeContext(AgentTaskContext context) {
        Map<String, Object> value = Map.ofEntries(
                Map.entry("tenantId", context.tenantId()),
                Map.entry("userId", context.userId()),
                Map.entry("parentTaskId", context.parentTaskId() == null ? "" : context.parentTaskId()),
                Map.entry("traceId", context.traceId()),
                Map.entry("spanId", context.spanId()),
                Map.entry("idempotencyKey", context.idempotencyKey()),
                Map.entry("deadline", context.deadline().toString()),
                Map.entry("capabilitySnapshotVersion", context.capabilitySnapshotVersion()),
                Map.entry("cancelled", context.cancellation().isCancelled()),
                Map.entry("budget", context.budget()),
                Map.entry("depth", context.depth()),
                Map.entry("allowedCapabilities", context.allowedCapabilities()),
                Map.entry("taskBrief", context.taskBrief()),
                Map.entry("references", context.references()));
        return write(value);
    }

    private AgentTaskContext readContext(String value) {
        try {
            JsonNode node = mapper.readTree(value);
            JsonNode budget = node.path("budget");
            CancellationToken cancellation = new CancellationToken();
            if (node.path("cancelled").asBoolean()) cancellation.cancel();
            String parentTaskId = node.path("parentTaskId").asText();
            return new AgentTaskContext(
                    node.path("tenantId").asText(),
                    node.path("userId").asText(),
                    parentTaskId.isBlank() ? null : parentTaskId,
                    node.path("traceId").asText(),
                    node.path("spanId").asText(),
                    node.path("idempotencyKey").asText(),
                    Instant.parse(node.path("deadline").asText()),
                    node.path("capabilitySnapshotVersion").asText("capability:legacy"),
                    cancellation,
                    new AgentTaskContext.Budget(
                            budget.path("maxTokens").asLong(),
                            budget.path("maxToolCalls").asInt(),
                            new BigDecimal(budget.path("maxCost").asText()),
                            budget.path("maxDepth").asInt(),
                            budget.path("maxChildren").asInt()),
                    node.path("depth").asInt(),
                    mapper.convertValue(node.path("allowedCapabilities"), STRING_SET),
                    node.path("taskBrief").asText(),
                    mapper.convertValue(node.path("references"), STRING_MAP));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid persisted agent task context", error);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize agent runtime value", error);
        }
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getTimestamp(name).toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    private static void requireUpdated(int updated, String type, String id) {
        if (updated != 1) throw new IllegalStateException("stale or missing " + type + ": " + id);
    }
}
