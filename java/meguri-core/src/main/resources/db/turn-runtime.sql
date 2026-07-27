CREATE TABLE IF NOT EXISTS turn_runtime (
    turn_id VARCHAR(128) PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255),
    payload_hash CHAR(64),
    request_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    manifest_json JSONB,
    result_json JSONB,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_turn_runtime_idempotency
    ON turn_runtime (user_id, client_id, session_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_turn_runtime_session
    ON turn_runtime (session_id, accepted_at);

CREATE TABLE IF NOT EXISTS turn_session_sequence (
    session_id VARCHAR(255) PRIMARY KEY,
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= 0)
);

CREATE TABLE IF NOT EXISTS turn_event (
    event_id VARCHAR(128) PRIMARY KEY,
    turn_id VARCHAR(128) NOT NULL REFERENCES turn_runtime(turn_id) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    protocol_version VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    data_json JSONB NOT NULL,
    metadata_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (session_id, sequence)
);

CREATE INDEX IF NOT EXISTS ix_turn_event_turn
    ON turn_event (turn_id, sequence);

CREATE TABLE IF NOT EXISTS turn_outbox (
    outbox_id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL UNIQUE REFERENCES turn_event(event_id) ON DELETE CASCADE,
    aggregate_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_turn_outbox_pending
    ON turn_outbox (available_at, outbox_id)
    WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS session_context_graph (
    user_id VARCHAR(255) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    snapshot_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, client_id, session_id)
);
