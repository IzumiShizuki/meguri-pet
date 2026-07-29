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
    failure_code VARCHAR(128),
    error TEXT,
    retry_of_turn_id VARCHAR(128) REFERENCES turn_runtime(turn_id),
    owner_id VARCHAR(255),
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE turn_runtime ADD COLUMN IF NOT EXISTS failure_code VARCHAR(128);
ALTER TABLE turn_runtime ADD COLUMN IF NOT EXISTS retry_of_turn_id VARCHAR(128) REFERENCES turn_runtime(turn_id);
ALTER TABLE turn_runtime ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);
ALTER TABLE turn_runtime ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
ALTER TABLE turn_runtime ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;
ALTER TABLE turn_runtime ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE turn_runtime ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_turn_runtime_idempotency
    ON turn_runtime (user_id, client_id, session_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_turn_runtime_session
    ON turn_runtime (session_id, accepted_at);

CREATE INDEX IF NOT EXISTS ix_turn_runtime_recovery
    ON turn_runtime (lease_until, accepted_at)
    WHERE status NOT IN ('completed', 'failed', 'cancelled');

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
    required_extension VARCHAR(255),
    event_type VARCHAR(128) NOT NULL,
    replay_policy VARCHAR(16) NOT NULL DEFAULT 'ALWAYS',
    data_json JSONB NOT NULL,
    metadata_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (session_id, sequence)
);

ALTER TABLE turn_event
    ADD COLUMN IF NOT EXISTS required_extension VARCHAR(255);

ALTER TABLE turn_event
    ADD COLUMN IF NOT EXISTS replay_policy VARCHAR(16) NOT NULL DEFAULT 'ALWAYS';

CREATE INDEX IF NOT EXISTS ix_turn_event_turn
    ON turn_event (turn_id, sequence);

CREATE UNIQUE INDEX IF NOT EXISTS uq_turn_event_terminal
    ON turn_event (turn_id)
    WHERE event_type IN ('turn.completed', 'turn.failed', 'turn.cancelled');

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
    claim_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE turn_outbox ADD COLUMN IF NOT EXISTS claim_owner VARCHAR(255);
ALTER TABLE turn_outbox ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_turn_outbox_pending
    ON turn_outbox (available_at, outbox_id)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS ix_turn_outbox_reclaim
    ON turn_outbox (lease_until, outbox_id)
    WHERE status = 'claimed';

CREATE TABLE IF NOT EXISTS session_context_graph (
    user_id VARCHAR(255) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    snapshot_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, client_id, session_id)
);
