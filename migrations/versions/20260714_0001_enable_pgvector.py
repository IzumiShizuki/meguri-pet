"""Enable the required pgvector extension.

Revision ID: 20260714_0001
Revises:
"""
from __future__ import annotations

from collections.abc import Sequence

from alembic import op


revision: str = "20260714_0001"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # PostgreSQL raises a hard error when the extension package is unavailable;
    # there is intentionally no non-vector fallback for the authoritative store.
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute(
        """
        DO $$
        BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
                RAISE EXCEPTION 'pgvector extension is required for Meguri memory';
            END IF;
        END
        $$
        """
    )


def downgrade() -> None:
    op.execute("DROP EXTENSION IF EXISTS vector")
