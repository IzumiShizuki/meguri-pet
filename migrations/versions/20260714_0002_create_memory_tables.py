"""Create authoritative memory tables and integrity constraints.

Revision ID: 20260714_0002
Revises: 20260714_0001
"""
from __future__ import annotations

from collections.abc import Sequence

from alembic import op
from pgvector.sqlalchemy import VECTOR
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "20260714_0002"
down_revision: str | Sequence[str] | None = "20260714_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


MEMORY_TYPES = (
    "user_profile",
    "user_preference",
    "important_person",
    "long_term_project",
    "commitment",
    "relationship_fact",
    "recurring_habit",
    "corrected_fact",
)


def values(items: tuple[str, ...]) -> str:
    return ", ".join(f"'{item}'" for item in items)


def upgrade() -> None:
    op.create_table(
        "identity_bindings",
        sa.Column("binding_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id", sa.String(100), nullable=False),
        sa.Column("user_id", sa.String(200), nullable=False),
        sa.Column("platform", sa.String(100), nullable=False),
        sa.Column("platform_user_id", sa.String(300), nullable=False),
        sa.Column("status", sa.String(40), nullable=False),
        sa.Column("verification_method", sa.String(100)),
        sa.Column("verified_at", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.CheckConstraint(
            "status IN ('pending_verification', 'active', 'unbound')",
            name="ck_identity_bindings_valid_status",
        ),
    )

    op.create_table(
        "memory_candidates",
        sa.Column("candidate_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id", sa.String(100), nullable=False),
        sa.Column("user_id", sa.String(200), nullable=False),
        sa.Column("memory_type", sa.String(50), nullable=False),
        sa.Column("content_text", sa.Text(), nullable=False),
        sa.Column("content_json", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column("sensitivity", sa.String(30), nullable=False),
        sa.Column("source_client_id", sa.String(100), nullable=False),
        sa.Column("source_session_id", sa.String(200), nullable=False),
        sa.Column("source_turn_id", sa.String(200), nullable=False),
        sa.Column("source_message_ids", postgresql.JSONB(), nullable=False, server_default=sa.text("'[]'::jsonb")),
        sa.Column("source_kind", sa.String(50), nullable=False),
        sa.Column("extraction_model", sa.String(300)),
        sa.Column("extraction_prompt_hash", sa.String(64)),
        sa.Column("provenance", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("status", sa.String(40), nullable=False),
        sa.Column("review_reason", sa.Text()),
        sa.Column("reviewed_by", sa.String(200)),
        sa.Column("reviewed_at", sa.DateTime(timezone=True)),
        sa.Column("accepted_memory_id", postgresql.UUID(as_uuid=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.CheckConstraint(
            f"memory_type IN ({values(MEMORY_TYPES)})",
            name="ck_memory_candidates_valid_memory_type",
        ),
        sa.CheckConstraint(
            "sensitivity IN ('normal', 'private', 'sensitive')",
            name="ck_memory_candidates_valid_sensitivity",
        ),
        sa.CheckConstraint(
            "status IN ('pending_review', 'processing', 'approved', 'rejected', 'expired')",
            name="ck_memory_candidates_valid_status",
        ),
        sa.CheckConstraint(
            "source_kind IN ('direct_user', 'llm_candidate', 'memoryos_import', 'mem0_shadow', 'admin')",
            name="ck_memory_candidates_valid_source_kind",
        ),
        sa.CheckConstraint(
            "confidence >= 0 AND confidence <= 1",
            name="ck_memory_candidates_confidence_range",
        ),
    )

    op.create_table(
        "memory_items",
        sa.Column("memory_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id", sa.String(100), nullable=False),
        sa.Column("user_id", sa.String(200), nullable=False),
        sa.Column("memory_type", sa.String(50), nullable=False),
        sa.Column("scope", sa.String(50), nullable=False),
        sa.Column("status", sa.String(40), nullable=False),
        sa.Column("canonical_key", sa.String(500)),
        sa.Column("current_version_id", postgresql.UUID(as_uuid=True)),
        sa.Column("importance", sa.Float(), nullable=False, server_default=sa.text("0.5")),
        sa.Column("confidence", sa.Float(), nullable=False, server_default=sa.text("0.5")),
        sa.Column("effective_at", sa.DateTime(timezone=True)),
        sa.Column("expires_at", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint(
            f"memory_type IN ({values(MEMORY_TYPES)})",
            name="ck_memory_items_valid_memory_type",
        ),
        sa.CheckConstraint(
            "scope IN ('global_user', 'client_private')",
            name="ck_memory_items_valid_scope",
        ),
        sa.CheckConstraint(
            "status IN ('active', 'superseded', 'expired', 'archived', 'deleted')",
            name="ck_memory_items_valid_status",
        ),
        sa.CheckConstraint("importance >= 0 AND importance <= 1", name="ck_memory_items_importance_range"),
        sa.CheckConstraint("confidence >= 0 AND confidence <= 1", name="ck_memory_items_confidence_range"),
        sa.CheckConstraint(
            "status <> 'active' OR current_version_id IS NOT NULL",
            name="ck_memory_items_active_has_current_version",
        ),
    )

    op.create_table(
        "memory_versions",
        sa.Column("version_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("memory_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("version_no", sa.Integer(), nullable=False),
        sa.Column("content_text", sa.Text(), nullable=False),
        sa.Column("content_json", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("language", sa.String(30)),
        sa.Column("relationship_stage", sa.String(100)),
        sa.Column("supersedes_version_id", postgresql.UUID(as_uuid=True)),
        sa.Column("change_reason", sa.Text(), nullable=False),
        sa.Column("provenance", postgresql.JSONB(), nullable=False),
        sa.Column("created_by_type", sa.String(30), nullable=False),
        sa.Column("created_by_id", sa.String(200)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.ForeignKeyConstraint(["memory_id"], ["memory_items.memory_id"], ondelete="CASCADE", name="fk_memory_versions_memory_id_memory_items"),
        sa.ForeignKeyConstraint(["supersedes_version_id"], ["memory_versions.version_id"], name="fk_memory_versions_supersedes_version_id_memory_versions"),
        sa.UniqueConstraint("memory_id", "version_no", name="uq_memory_versions_item_number"),
        sa.UniqueConstraint("memory_id", "version_id", name="uq_memory_versions_item_version"),
        sa.CheckConstraint(
            "created_by_type IN ('user', 'admin', 'policy', 'system', 'import')",
            name="ck_memory_versions_valid_created_by_type",
        ),
    )

    op.create_foreign_key(
        "fk_memory_items_current_version_same_item",
        "memory_items",
        "memory_versions",
        ["memory_id", "current_version_id"],
        ["memory_id", "version_id"],
        deferrable=True,
        initially="DEFERRED",
    )
    op.create_foreign_key(
        "fk_memory_candidates_accepted_memory_id_memory_items",
        "memory_candidates",
        "memory_items",
        ["accepted_memory_id"],
        ["memory_id"],
    )

    op.create_table(
        "memory_embeddings",
        sa.Column("embedding_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("version_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("embedding_model", sa.String(300), nullable=False),
        sa.Column("embedding_revision", sa.String(300), nullable=False),
        sa.Column("embedding_dimension", sa.Integer(), nullable=False),
        sa.Column("embedding", VECTOR(1024), nullable=False),
        sa.Column("content_sha256", sa.String(64), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.ForeignKeyConstraint(["version_id"], ["memory_versions.version_id"], ondelete="CASCADE", name="fk_memory_embeddings_version_id_memory_versions"),
        sa.UniqueConstraint("version_id", "embedding_model", "embedding_revision", name="uq_memory_embeddings_version_model_revision"),
        sa.CheckConstraint("embedding_dimension = 1024", name="ck_memory_embeddings_dimension_1024"),
        sa.CheckConstraint("status IN ('ready', 'failed', 'stale')", name="ck_memory_embeddings_valid_status"),
    )

    op.create_table(
        "memory_feedback",
        sa.Column("feedback_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id", sa.String(100), nullable=False),
        sa.Column("user_id", sa.String(200), nullable=False),
        sa.Column("memory_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("version_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("feedback_kind", sa.String(40), nullable=False),
        sa.Column("query_text", sa.Text()),
        sa.Column("hit_rank", sa.Integer()),
        sa.Column("details", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.ForeignKeyConstraint(["memory_id"], ["memory_items.memory_id"], ondelete="CASCADE", name="fk_memory_feedback_memory_id_memory_items"),
        sa.ForeignKeyConstraint(["version_id"], ["memory_versions.version_id"], name="fk_memory_feedback_version_id_memory_versions"),
        sa.CheckConstraint(
            "feedback_kind IN ('helpful', 'wrong', 'stale', 'corrected', 'user_deleted', 'false_recall')",
            name="ck_memory_feedback_valid_feedback_kind",
        ),
        sa.CheckConstraint("hit_rank IS NULL OR hit_rank >= 1", name="ck_memory_feedback_positive_hit_rank"),
    )

    op.create_table(
        "session_summaries",
        sa.Column("summary_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id", sa.String(100), nullable=False),
        sa.Column("user_id", sa.String(200), nullable=False),
        sa.Column("client_id", sa.String(100), nullable=False),
        sa.Column("session_id", sa.String(200), nullable=False),
        sa.Column("summary_text", sa.Text(), nullable=False),
        sa.Column("summary_json", postgresql.JSONB(), nullable=False),
        sa.Column("source_range", postgresql.JSONB(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False, server_default=sa.text("1")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.UniqueConstraint("tenant_id", "user_id", "client_id", "session_id", name="uq_session_summaries_identity"),
    )

    op.create_table(
        "memory_audit_log",
        sa.Column("audit_id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("tenant_id", sa.String(100), nullable=False),
        sa.Column("request_id", sa.String(200), nullable=False),
        sa.Column("action", sa.String(50), nullable=False),
        sa.Column("aggregate_type", sa.String(50), nullable=False),
        sa.Column("aggregate_id", sa.String(200), nullable=False),
        sa.Column("actor_type", sa.String(30), nullable=False),
        sa.Column("actor_id", sa.String(200)),
        sa.Column("details", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.CheckConstraint(
            "action IN ('candidate_create', 'approve', 'reject', 'item_create', 'supersede', 'delete', 'restore', 'export', 'hard_delete', 'identity_bind', 'identity_unbind')",
            name="ck_memory_audit_log_valid_action",
        ),
        sa.CheckConstraint(
            "actor_type IN ('user', 'admin', 'policy', 'system', 'import')",
            name="ck_memory_audit_log_valid_actor_type",
        ),
    )

    op.create_table(
        "memory_idempotency",
        sa.Column("idempotency_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id", sa.String(100), nullable=False),
        sa.Column("operation", sa.String(100), nullable=False),
        sa.Column("request_id", sa.String(200), nullable=False),
        sa.Column("response_json", postgresql.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.UniqueConstraint("tenant_id", "operation", "request_id", name="uq_memory_idempotency_request"),
    )

    op.execute(
        """
        CREATE FUNCTION meguri_reject_memory_version_update()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'memory_versions are immutable';
        END
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_memory_versions_immutable
        BEFORE UPDATE ON memory_versions
        FOR EACH ROW EXECUTE FUNCTION meguri_reject_memory_version_update()
        """
    )
    op.execute(
        """
        CREATE FUNCTION meguri_reject_audit_mutation()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'memory_audit_log is append-only';
        END
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_memory_audit_append_only
        BEFORE UPDATE OR DELETE ON memory_audit_log
        FOR EACH ROW EXECUTE FUNCTION meguri_reject_audit_mutation()
        """
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS trg_memory_audit_append_only ON memory_audit_log")
    op.execute("DROP FUNCTION IF EXISTS meguri_reject_audit_mutation()")
    op.execute("DROP TRIGGER IF EXISTS trg_memory_versions_immutable ON memory_versions")
    op.execute("DROP FUNCTION IF EXISTS meguri_reject_memory_version_update()")
    op.drop_table("memory_idempotency")
    op.drop_table("memory_audit_log")
    op.drop_table("session_summaries")
    op.drop_table("memory_feedback")
    op.drop_table("memory_embeddings")
    op.drop_constraint("fk_memory_candidates_accepted_memory_id_memory_items", "memory_candidates", type_="foreignkey")
    op.drop_constraint("fk_memory_items_current_version_same_item", "memory_items", type_="foreignkey")
    op.drop_table("memory_versions")
    op.drop_table("memory_items")
    op.drop_table("memory_candidates")
    op.drop_table("identity_bindings")
