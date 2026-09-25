"""Switch the rebuildable memory vector projection to DashScope 2048 dimensions.

Revision ID: 20260725_0005
Revises: 20260714_0004
"""
from __future__ import annotations

from collections.abc import Sequence

from alembic import op
from pgvector.sqlalchemy import Vector
import sqlalchemy as sa


revision: str = "20260725_0005"
down_revision: str | Sequence[str] | None = "20260714_0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # Embeddings are an explicitly rebuildable projection of memory_versions.
    # BGE-M3 and text-embedding-v4 vectors must never share one vector space,
    # so remove only the derived rows before changing the fixed pgvector width.
    op.execute("DELETE FROM memory_embeddings")
    op.drop_constraint("ck_memory_embeddings_dimension_1024", "memory_embeddings", type_="check")
    op.alter_column(
        "memory_embeddings",
        "embedding",
        type_=Vector(2048),
        existing_type=Vector(1024),
        postgresql_using="embedding::vector(2048)",
    )
    op.create_check_constraint(
        "ck_memory_embeddings_dimension_2048",
        "memory_embeddings",
        "embedding_dimension = 2048",
    )


def downgrade() -> None:
    op.execute("DELETE FROM memory_embeddings")
    op.drop_constraint("ck_memory_embeddings_dimension_2048", "memory_embeddings", type_="check")
    op.alter_column(
        "memory_embeddings",
        "embedding",
        type_=Vector(1024),
        existing_type=Vector(2048),
        postgresql_using="embedding::vector(1024)",
    )
    op.create_check_constraint(
        "ck_memory_embeddings_dimension_1024",
        "memory_embeddings",
        "embedding_dimension = 1024",
    )
