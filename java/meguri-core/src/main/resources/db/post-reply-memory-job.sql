CREATE TABLE IF NOT EXISTS post_reply_memory_job (
    job_id varchar(64) PRIMARY KEY,
    turn_id varchar(128) NOT NULL,
    response_digest char(64) NOT NULL,
    trace_id varchar(128) NOT NULL,
    request_json jsonb NOT NULL,
    response_json jsonb NOT NULL,
    cancellation_policy varchar(32) NOT NULL,
    cancelled_after_reply boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL,
    owner_id varchar(160),
    lease_until timestamptz,
    last_error varchar(2000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_post_reply_memory_turn_digest UNIQUE (turn_id, response_digest),
    CONSTRAINT ck_post_reply_memory_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'DEAD_LETTER')
    ),
    CONSTRAINT ck_post_reply_memory_lease CHECK (
        (status = 'RUNNING' AND owner_id IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND owner_id IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_post_reply_memory_claim
    ON post_reply_memory_job (available_at, created_at)
    WHERE status IN ('PENDING', 'RUNNING');
