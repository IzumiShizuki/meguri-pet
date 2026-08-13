CREATE TABLE IF NOT EXISTS context_build_trace (
    trace_id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    graph_revision BIGINT NOT NULL CHECK (graph_revision >= 0),
    request_digest CHAR(64) NOT NULL,
    bundle_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_context_build_trace_conversation
    ON context_build_trace (conversation_id, created_at);

CREATE TABLE IF NOT EXISTS context_topic_segment (
    segment_id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    label VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_context_topic_segment_conversation
    ON context_topic_segment (conversation_id, created_at);

CREATE TABLE IF NOT EXISTS context_precompression_job (
    job_id VARCHAR(128) PRIMARY KEY,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    user_id VARCHAR(255) NOT NULL,
    client_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    graph_revision BIGINT NOT NULL CHECK (graph_revision >= 0),
    source_message_ids JSONB NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    strategy_revision VARCHAR(255) NOT NULL DEFAULT 'context-refactoring-v1-deterministic',
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    claim_owner VARCHAR(255),
    claim_token VARCHAR(128),
    summary_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE context_precompression_job
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(255);
ALTER TABLE context_precompression_job
    ADD COLUMN IF NOT EXISTS client_id VARCHAR(255);
ALTER TABLE context_precompression_job
    ADD COLUMN IF NOT EXISTS source_message_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE context_precompression_job
    ADD COLUMN IF NOT EXISTS claim_owner VARCHAR(255);
ALTER TABLE context_precompression_job
    ADD COLUMN IF NOT EXISTS claim_token VARCHAR(128);
ALTER TABLE context_precompression_job
    ADD COLUMN IF NOT EXISTS strategy_revision VARCHAR(255)
    NOT NULL DEFAULT 'context-refactoring-v1-deterministic';

CREATE INDEX IF NOT EXISTS ix_context_precompression_recovery
    ON context_precompression_job (available_at, created_at)
    WHERE status IN ('PENDING', 'RUNNING');
