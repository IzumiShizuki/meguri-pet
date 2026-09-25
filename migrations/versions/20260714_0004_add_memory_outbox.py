"""Add reliable memory outbox.

Revision ID: 20260714_0004
Revises: 20260714_0003
"""
from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "20260714_0004"
down_revision: str | Sequence[str] | None = "20260714_0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "memory_outbox",
        sa.Column("outbox_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_type", sa.String(100), nullable=False),
        sa.Column("aggregate_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("payload", postgresql.JSONB(), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("locked_at", sa.DateTime(timezone=True)),
        sa.Column("locked_by", sa.String(200)),
        sa.Column("last_error", sa.Text()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("completed_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint(
            "status IN ('pending', 'processing', 'completed', 'failed', 'dead_letter')",
            name="ck_memory_outbox_valid_status",
        ),
    )
    op.create_index(
        "ix_memory_outbox_status_available_at",
        "memory_outbox",
        ["status", "available_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_memory_outbox_status_available_at", table_name="memory_outbox")
    op.drop_table("memory_outbox")
