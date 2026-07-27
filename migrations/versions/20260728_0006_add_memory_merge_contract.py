"""Add memory risk, merge policy, and optimistic base version.

Revision ID: 20260728_0006
Revises: 20260725_0005
"""
from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "20260728_0006"
down_revision: str | Sequence[str] | None = "20260725_0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "memory_candidates",
        sa.Column(
            "risk_level",
            sa.String(30),
            nullable=False,
            server_default=sa.text("'moderate'"),
        ),
    )
    op.add_column(
        "memory_candidates",
        sa.Column(
            "merge_policy",
            sa.String(30),
            nullable=False,
            server_default=sa.text("'review'"),
        ),
    )
    op.add_column(
        "memory_candidates",
        sa.Column("base_version_id", postgresql.UUID(as_uuid=True)),
    )
    op.create_check_constraint(
        "ck_memory_candidates_valid_risk_level",
        "memory_candidates",
        "risk_level IN ('low', 'moderate', 'high', 'prohibited')",
    )
    op.create_check_constraint(
        "ck_memory_candidates_valid_merge_policy",
        "memory_candidates",
        "merge_policy IN ('review', 'create_only', 'supersede')",
    )
    op.create_foreign_key(
        "fk_memory_candidates_base_version_id_memory_versions",
        "memory_candidates",
        "memory_versions",
        ["base_version_id"],
        ["version_id"],
        ondelete="SET NULL",
    )
    op.create_index(
        "uq_memory_items_active_canonical_key",
        "memory_items",
        ["tenant_id", "user_id", "memory_type", "canonical_key"],
        unique=True,
        postgresql_where=sa.text("status = 'active' AND canonical_key IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index(
        "uq_memory_items_active_canonical_key",
        table_name="memory_items",
    )
    op.drop_constraint(
        "fk_memory_candidates_base_version_id_memory_versions",
        "memory_candidates",
        type_="foreignkey",
    )
    op.drop_constraint(
        "ck_memory_candidates_valid_merge_policy",
        "memory_candidates",
        type_="check",
    )
    op.drop_constraint(
        "ck_memory_candidates_valid_risk_level",
        "memory_candidates",
        type_="check",
    )
    op.drop_column("memory_candidates", "base_version_id")
    op.drop_column("memory_candidates", "merge_policy")
    op.drop_column("memory_candidates", "risk_level")
