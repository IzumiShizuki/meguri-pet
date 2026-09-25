"""Create exact-search-first memory indexes.

Revision ID: 20260714_0003
Revises: 20260714_0002
"""
from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa


revision: str = "20260714_0003"
down_revision: str | Sequence[str] | None = "20260714_0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_index(
        "uq_identity_bindings_active_platform_identity",
        "identity_bindings",
        ["tenant_id", "platform", "platform_user_id"],
        unique=True,
        postgresql_where=sa.text("status = 'active'"),
    )
    op.create_index("ix_memory_candidates_status_created_at", "memory_candidates", ["status", "created_at"])
    op.create_index(
        "ix_memory_items_tenant_user_status_type",
        "memory_items",
        ["tenant_id", "user_id", "status", "memory_type"],
    )
    op.create_index(
        "ix_memory_items_tenant_user_canonical_key",
        "memory_items",
        ["tenant_id", "user_id", "canonical_key"],
    )
    op.create_index("ix_memory_versions_memory_version_no", "memory_versions", ["memory_id", "version_no"])
    op.create_index(
        "ix_memory_embeddings_version_model_revision",
        "memory_embeddings",
        ["version_id", "embedding_model", "embedding_revision"],
    )
    op.create_index(
        "ix_session_summaries_identity",
        "session_summaries",
        ["tenant_id", "user_id", "client_id", "session_id"],
    )
    op.create_index("ix_memory_audit_request_id", "memory_audit_log", ["request_id"])
    op.execute(
        """
        CREATE INDEX ix_memory_versions_content_fts
        ON memory_versions
        USING gin (to_tsvector('simple', content_text))
        """
    )
    # HNSW is deliberately absent until scale and recall benchmarks justify it.


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_memory_versions_content_fts")
    op.drop_index("ix_memory_audit_request_id", table_name="memory_audit_log")
    op.drop_index("ix_session_summaries_identity", table_name="session_summaries")
    op.drop_index("ix_memory_embeddings_version_model_revision", table_name="memory_embeddings")
    op.drop_index("ix_memory_versions_memory_version_no", table_name="memory_versions")
    op.drop_index("ix_memory_items_tenant_user_canonical_key", table_name="memory_items")
    op.drop_index("ix_memory_items_tenant_user_status_type", table_name="memory_items")
    op.drop_index("ix_memory_candidates_status_created_at", table_name="memory_candidates")
    op.drop_index("uq_identity_bindings_active_platform_identity", table_name="identity_bindings")
