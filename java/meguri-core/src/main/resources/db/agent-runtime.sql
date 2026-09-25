CREATE TABLE IF NOT EXISTS skill_execution (
    execution_id VARCHAR(128) PRIMARY KEY,
    turn_id VARCHAR(128),
    skill_id VARCHAR(128) NOT NULL,
    capability_snapshot_version VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'PENDING', 'RUNNING', 'WAITING_REMOTE_AGENT', 'RESUMING',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
    )),
    deadline_at TIMESTAMPTZ NOT NULL,
    error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE skill_execution
    ADD COLUMN IF NOT EXISTS capability_snapshot_version VARCHAR(256)
        NOT NULL DEFAULT 'capability:legacy';

CREATE INDEX IF NOT EXISTS ix_skill_execution_turn
    ON skill_execution (turn_id, created_at);
CREATE INDEX IF NOT EXISTS ix_skill_execution_resumable
    ON skill_execution (status, updated_at)
    WHERE status IN ('WAITING_REMOTE_AGENT', 'RESUMING');

CREATE TABLE IF NOT EXISTS skill_step_execution (
    step_execution_id VARCHAR(128) PRIMARY KEY,
    execution_id VARCHAR(128) NOT NULL REFERENCES skill_execution(execution_id) ON DELETE CASCADE,
    step_key VARCHAR(128) NOT NULL,
    execution_domain VARCHAR(32) NOT NULL CHECK (execution_domain IN (
        'INLINE', 'NON_BLOCKING_IO', 'BLOCKING_IO', 'CPU',
        'PROVIDER', 'REMOTE_AGENT', 'BACKGROUND'
    )),
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
    )),
    result_json JSONB,
    error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_skill_step_execution_parent
    ON skill_step_execution (execution_id, created_at);
CREATE INDEX IF NOT EXISTS ix_skill_step_execution_waiting
    ON skill_step_execution (status, updated_at)
    WHERE status = 'WAITING';

CREATE TABLE IF NOT EXISTS agent_task (
    task_id VARCHAR(128) PRIMARY KEY,
    execution_id VARCHAR(128) NOT NULL REFERENCES skill_execution(execution_id) ON DELETE CASCADE,
    step_execution_id VARCHAR(128) NOT NULL REFERENCES skill_step_execution(step_execution_id) ON DELETE CASCADE,
    parent_task_id VARCHAR(128),
    resource_id VARCHAR(128) NOT NULL,
    remote_task_id VARCHAR(256),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'CREATED', 'QUEUED', 'RUNNING', 'WAITING_EXTERNAL',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
    )),
    context_json JSONB NOT NULL,
    result_json JSONB,
    error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_task_idempotency UNIQUE (tenant_id, user_id, idempotency_key),
    CONSTRAINT uq_agent_task_remote UNIQUE (remote_task_id)
);

CREATE INDEX IF NOT EXISTS ix_agent_task_parent
    ON agent_task (parent_task_id, created_at);
CREATE INDEX IF NOT EXISTS ix_agent_task_parent_budget
    ON agent_task (parent_task_id)
    INCLUDE (context_json);
CREATE INDEX IF NOT EXISTS ix_agent_task_execution
    ON agent_task (execution_id, created_at);
CREATE INDEX IF NOT EXISTS ix_agent_task_resumable
    ON agent_task (status, updated_at)
    WHERE status IN ('CREATED', 'QUEUED', 'RUNNING', 'WAITING_EXTERNAL');
CREATE INDEX IF NOT EXISTS ix_agent_task_quota
    ON agent_task (tenant_id, user_id, status)
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_EXTERNAL');
