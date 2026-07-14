from __future__ import annotations

import hashlib
from datetime import datetime
from typing import Any

from pydantic import Field
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection

from .models import StrictModel


CORE_TABLES = (
    "identity_bindings",
    "memory_candidates",
    "memory_items",
    "memory_versions",
    "memory_embeddings",
    "memory_feedback",
    "session_summaries",
    "memory_audit_log",
    "memory_outbox",
)


class RecoveryValidationReport(StrictModel):
    checked_at: datetime
    database_revision: str
    expected_database_revision: str | None = None
    table_counts: dict[str, int]
    invalid_current_versions: int = Field(ge=0)
    invalid_active_items: int = Field(ge=0)
    embedding_hash_mismatches: int = Field(ge=0)
    audit_replay_mismatches: int = Field(ge=0)
    errors: list[str] = Field(default_factory=list)
    passed: bool


def replay_audit_states(events: list[dict[str, Any]]) -> dict[str, str]:
    states: dict[str, str] = {}
    for event in events:
        aggregate_id = str(event["aggregate_id"])
        action = str(event["action"])
        if action in {"item_create", "supersede", "restore"}:
            states[aggregate_id] = "active"
        elif action == "delete":
            states[aggregate_id] = "deleted"
        elif action == "hard_delete":
            states[aggregate_id] = "hard_deleted"
    return states


async def validate_memory_recovery(
    connection: AsyncConnection,
    *,
    expected_database_revision: str | None = None,
) -> RecoveryValidationReport:
    revision = str(
        await connection.scalar(text("SELECT version_num FROM alembic_version"))
    )
    counts: dict[str, int] = {}
    for table in CORE_TABLES:
        counts[table] = int(
            await connection.scalar(text(f"SELECT count(*) FROM {table}")) or 0
        )

    invalid_current_versions = int(
        await connection.scalar(
            text(
                """
                SELECT count(*)
                FROM memory_items AS item
                LEFT JOIN memory_versions AS version
                  ON version.version_id = item.current_version_id
                WHERE version.version_id IS NULL
                   OR version.memory_id <> item.memory_id
                """
            )
        )
        or 0
    )
    invalid_active_items = int(
        await connection.scalar(
            text(
                """
                SELECT count(*)
                FROM memory_items AS item
                WHERE item.status = 'active'
                  AND NOT EXISTS (
                    SELECT 1 FROM memory_versions AS version
                    WHERE version.memory_id = item.memory_id
                  )
                """
            )
        )
        or 0
    )

    embedding_rows = (
        await connection.execute(
            text(
                """
                SELECT embedding.content_sha256, version.content_text
                FROM memory_embeddings AS embedding
                JOIN memory_versions AS version
                  ON version.version_id = embedding.version_id
                WHERE embedding.status = 'ready'
                """
            )
        )
    ).all()
    embedding_hash_mismatches = sum(
        hashlib.sha256(content.encode("utf-8")).hexdigest() != stored_hash
        for stored_hash, content in embedding_rows
    )

    audit_rows = (
        await connection.execute(
            text(
                """
                SELECT aggregate_id, action
                FROM memory_audit_log
                WHERE aggregate_type = 'memory'
                  AND action IN (
                    'item_create', 'supersede', 'delete', 'restore', 'hard_delete'
                  )
                ORDER BY audit_id
                """
            )
        )
    ).mappings().all()
    replayed = replay_audit_states([dict(row) for row in audit_rows])
    actual_rows = (
        await connection.execute(
            text("SELECT memory_id::text AS memory_id, status FROM memory_items")
        )
    ).mappings().all()
    actual = {str(row["memory_id"]): str(row["status"]) for row in actual_rows}
    audit_replay_mismatches = 0
    for memory_id, replayed_status in replayed.items():
        actual_status = actual.get(memory_id)
        if replayed_status == "hard_deleted":
            audit_replay_mismatches += actual_status is not None
        elif replayed_status in {"active", "deleted"}:
            audit_replay_mismatches += actual_status != replayed_status

    errors: list[str] = []
    if expected_database_revision and revision != expected_database_revision:
        errors.append("database_revision_mismatch")
    if invalid_current_versions:
        errors.append("invalid_current_version_reference")
    if invalid_active_items:
        errors.append("active_item_without_version")
    if embedding_hash_mismatches:
        errors.append("embedding_content_hash_mismatch")
    if audit_replay_mismatches:
        errors.append("audit_replay_mismatch")
    return RecoveryValidationReport(
        checked_at=datetime.now().astimezone(),
        database_revision=revision,
        expected_database_revision=expected_database_revision,
        table_counts=counts,
        invalid_current_versions=invalid_current_versions,
        invalid_active_items=invalid_active_items,
        embedding_hash_mismatches=embedding_hash_mismatches,
        audit_replay_mismatches=audit_replay_mismatches,
        errors=errors,
        passed=not errors,
    )
