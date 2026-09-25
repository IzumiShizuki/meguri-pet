CREATE TABLE IF NOT EXISTS client_binding (
    client_instance_id TEXT PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    meguri_user_id VARCHAR(255) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    platform_actor_hash CHAR(64),
    client_version VARCHAR(128) NOT NULL,
    selected_protocol_version VARCHAR(32) NOT NULL,
    server_capabilities_revision VARCHAR(80) NOT NULL,
    capabilities JSONB NOT NULL,
    permissions JSONB NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CHECK (client_id IN ('airi', 'astrbot', 'desktop_pet', 'website', 'custom'))
);

ALTER TABLE client_binding
    ALTER COLUMN client_instance_id TYPE TEXT
    USING client_instance_id::text;

ALTER TABLE client_binding
    ADD COLUMN IF NOT EXISTS platform_actor_hash CHAR(64);

CREATE INDEX IF NOT EXISTS ix_client_binding_identity
    ON client_binding (tenant_id, meguri_user_id, client_id);

CREATE TABLE IF NOT EXISTS session_scope (
    session_id VARCHAR(255) PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    meguri_user_id VARCHAR(255) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    platform VARCHAR(128),
    conversation_scope_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, client_id, conversation_scope_hash)
);
