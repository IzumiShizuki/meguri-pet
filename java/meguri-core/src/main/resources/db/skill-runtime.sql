CREATE TABLE IF NOT EXISTS external_skill (
    skill_id text PRIMARY KEY,
    source_id text NOT NULL,
    external_id text NOT NULL,
    manifest jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_id, external_id)
);

CREATE TABLE IF NOT EXISTS external_skill_revision (
    skill_id text NOT NULL REFERENCES external_skill(skill_id),
    digest char(64) NOT NULL,
    revision jsonb NOT NULL,
    imported_at timestamptz NOT NULL,
    PRIMARY KEY (skill_id, digest)
);

CREATE TABLE IF NOT EXISTS external_skill_binding (
    skill_id text PRIMARY KEY REFERENCES external_skill(skill_id),
    active_digest char(64),
    state text NOT NULL CHECK (state IN ('DISABLED', 'ENABLED')),
    updated_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS external_skill_audit (
    event_id text PRIMARY KEY,
    source_id text,
    external_id text,
    skill_id text,
    digest char(64),
    actor text NOT NULL,
    transition text NOT NULL,
    status text NOT NULL,
    failure_code text,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_external_skill_audit_skill
    ON external_skill_audit(skill_id, occurred_at);
