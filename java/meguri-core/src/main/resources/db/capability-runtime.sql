CREATE TABLE IF NOT EXISTS capability_definition (
    capability_id       text        NOT NULL,
    version             text        NOT NULL,
    kind                text        NOT NULL,
    owner_name          text        NOT NULL,
    descriptor          jsonb       NOT NULL,
    schema_fingerprint  text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (capability_id, version)
);

CREATE TABLE IF NOT EXISTS capability_binding (
    tenant_id       text        NOT NULL,
    capability_id   text        NOT NULL,
    active_version  text        NOT NULL,
    enabled         boolean     NOT NULL DEFAULT true,
    draining        boolean     NOT NULL DEFAULT false,
    health          text        NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, capability_id),
    FOREIGN KEY (capability_id, active_version)
        REFERENCES capability_definition(capability_id, version)
);

CREATE TABLE IF NOT EXISTS capability_execution (
    execution_id        uuid        PRIMARY KEY,
    operation_id        text        NOT NULL UNIQUE,
    tenant_id           text        NOT NULL,
    user_id             text        NOT NULL,
    client_id           text        NOT NULL,
    turn_id             text        NOT NULL,
    trace_id            text        NOT NULL,
    snapshot_id         text        NOT NULL,
    capability_id       text        NOT NULL,
    capability_version  text        NOT NULL,
    idempotency_key     text,
    request_digest      char(64)    NOT NULL,
    status              text        NOT NULL,
    error_code          text,
    result_data         jsonb,
    result_display      text,
    result_source       jsonb,
    result_warnings     jsonb,
    retryable           boolean     NOT NULL DEFAULT false,
    started_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    FOREIGN KEY (capability_id, capability_version)
        REFERENCES capability_definition(capability_id, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_capability_operation_idempotency
    ON capability_execution(tenant_id, user_id, capability_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS capability_approval (
    approval_id     uuid        PRIMARY KEY,
    operation_id    text,
    capability_id   text        NOT NULL,
    capability_version text,
    snapshot_id     text        NOT NULL,
    turn_id         text        NOT NULL,
    trace_id        text        NOT NULL,
    tenant_id       text        NOT NULL,
    user_id         text        NOT NULL,
    client_id       text        NOT NULL,
    idempotency_key text,
    request_digest  char(64)    NOT NULL,
    decision        text        NOT NULL CHECK (decision IN ('PENDING', 'ACCEPT', 'DECLINE', 'CANCEL')),
    actor            text,
    created_at       timestamptz NOT NULL,
    resolved_at      timestamptz
);

CREATE TABLE IF NOT EXISTS capability_audit (
    event_id            uuid        PRIMARY KEY,
    occurred_at         timestamptz NOT NULL,
    turn_id             text        NOT NULL,
    trace_id            text        NOT NULL,
    snapshot_id         text        NOT NULL,
    tenant_id           text        NOT NULL,
    user_id             text        NOT NULL,
    client_id           text        NOT NULL,
    capability_id       text        NOT NULL,
    capability_version  text,
    operation_id        text,
    idempotency_key     text,
    request_digest      char(64)    NOT NULL,
    result_digest       char(64)    NOT NULL,
    approval_id         uuid,
    approval_decision   text,
    phase               text        NOT NULL,
    attempt             integer     NOT NULL,
    status              text        NOT NULL,
    error_code          text,
    duration_ms         bigint      NOT NULL,
    external_result     boolean     NOT NULL,
    retryable           boolean     NOT NULL
);

ALTER TABLE capability_execution
    ADD COLUMN IF NOT EXISTS request_digest char(64)
        NOT NULL DEFAULT repeat('0', 64);
ALTER TABLE capability_audit
    ADD COLUMN IF NOT EXISTS request_digest char(64)
        NOT NULL DEFAULT repeat('0', 64);
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS capability_version text;
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS snapshot_id text NOT NULL DEFAULT 'legacy';
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS turn_id text NOT NULL DEFAULT 'legacy';
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS trace_id text NOT NULL DEFAULT 'legacy';
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS tenant_id text NOT NULL DEFAULT 'legacy';
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS user_id text NOT NULL DEFAULT 'legacy';
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS client_id text NOT NULL DEFAULT 'legacy';
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS idempotency_key text;
ALTER TABLE capability_approval
    ADD COLUMN IF NOT EXISTS request_digest char(64)
        NOT NULL DEFAULT repeat('0', 64);
ALTER TABLE capability_audit
    ADD COLUMN IF NOT EXISTS result_digest char(64)
        NOT NULL DEFAULT repeat('0', 64);

CREATE INDEX IF NOT EXISTS ix_capability_audit_trace ON capability_audit(trace_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_capability_execution_turn ON capability_execution(turn_id, started_at);

CREATE TABLE IF NOT EXISTS capability_mcp_source (
    source_id                   text        PRIMARY KEY,
    endpoint                    text        NOT NULL,
    maximum_protocol            integer     NOT NULL CHECK (maximum_protocol > 0),
    authorization_environment   text,
    headers                     jsonb       NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(headers) = 'object'),
    allow_insecure_localhost    boolean     NOT NULL DEFAULT false,
    updated_at                  timestamptz NOT NULL DEFAULT now()
);
