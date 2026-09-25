CREATE TABLE IF NOT EXISTS persona_profile_revision (
    persona_id text NOT NULL, revision text NOT NULL, core_traits jsonb NOT NULL,
    speech_style jsonb NOT NULL, hard_boundaries jsonb NOT NULL,
    canonical_source_revision text NOT NULL, status text NOT NULL CHECK (status IN ('ACTIVE','DEPRECATED')),
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (persona_id, revision)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_persona_active ON persona_profile_revision(persona_id) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS persona_user_profile (
    user_id text PRIMARY KEY, revision text NOT NULL,
    display_name text NOT NULL DEFAULT '', locale text NOT NULL DEFAULT '',
    communication_preferences jsonb NOT NULL DEFAULT '[]',
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS relationship_state (
    user_id text PRIMARY KEY, stage text NOT NULL, familiarity double precision NOT NULL CHECK (familiarity BETWEEN 0 AND 1),
    trust double precision NOT NULL CHECK (trust BETWEEN 0 AND 1), recent_tension double precision NOT NULL CHECK (recent_tension BETWEEN 0 AND 1),
    boundary_flags jsonb NOT NULL DEFAULT '[]', version bigint NOT NULL, source text NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS persona_state_audit (
    audit_id uuid PRIMARY KEY, subject_id text NOT NULL, state_type text NOT NULL,
    old_value jsonb NOT NULL, new_value jsonb NOT NULL, trigger_type text NOT NULL,
    trigger_id text NOT NULL, policy_revision text NOT NULL, actor_id text NOT NULL,
    occurred_at timestamptz NOT NULL, version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_persona_audit_subject ON persona_state_audit(subject_id, state_type, created_at);
CREATE TABLE IF NOT EXISTS persona_scene_state (
    conversation_id text PRIMARY KEY, scene_type text NOT NULL, phase text NOT NULL,
    confidence double precision NOT NULL, evidence_count integer NOT NULL, started_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL, changed_at timestamptz NOT NULL, cooldown_until timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version > 0)
);
CREATE TABLE IF NOT EXISTS persona_runtime_override (
    override_id text PRIMARY KEY, override_type text NOT NULL CHECK (override_type <> 'RELATIONSHIP'),
    value jsonb NOT NULL, scope text NOT NULL, scope_id text NOT NULL, source text NOT NULL,
    expires_at timestamptz NULL, version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_persona_override_scope ON persona_runtime_override(scope, scope_id, expires_at);

CREATE TABLE IF NOT EXISTS persona_interaction_state (
    turn_id text PRIMARY KEY, session_id text NOT NULL, user_id text NOT NULL,
    state_json jsonb NOT NULL, version bigint NOT NULL CHECK (version = 1),
    created_at timestamptz NOT NULL
);
