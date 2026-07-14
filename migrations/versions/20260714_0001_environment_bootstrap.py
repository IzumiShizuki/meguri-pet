"""Enable pgvector for the isolated Meguri database.

Revision ID: 20260714_0001
Revises: None
Create Date: 2026-07-14
"""

from typing import Sequence, Union

from alembic import op


revision: str = "20260714_0001"
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")


def downgrade() -> None:
    op.execute("DROP EXTENSION IF EXISTS vector")
