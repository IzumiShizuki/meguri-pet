from __future__ import annotations

import hashlib
from collections.abc import Sequence
from datetime import datetime
from typing import Any, Protocol
from uuid import UUID

from pydantic import Field, model_validator
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection

from .enums import MemoryScope, MemoryType, SearchMode
from .models import MemoryHit, MemorySearchQuery, StrictModel


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


class RecoveryRecallCase(StrictModel):
    case_id: str = Field(min_length=1, max_length=200)
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    query: str = Field(min_length=1, max_length=4000)
    canonical_key: str | None = Field(default=None, min_length=1, max_length=500)
    expected_memory_ids: list[UUID] = Field(min_length=1, max_length=50)
    expected_version_ids: dict[UUID, UUID] = Field(default_factory=dict)
    minimum_recall_at_k: float = Field(default=1.0, ge=0, le=1)
    limit: int = Field(default=5, ge=1, le=50)
    memory_types: list[MemoryType] = Field(default_factory=list)
    scopes: list[MemoryScope] = Field(
        default_factory=lambda: [MemoryScope.GLOBAL_USER]
    )
    modes: list[SearchMode] = Field(default_factory=lambda: [SearchMode.HYBRID])
    token_budget: int = Field(default=1200, ge=64, le=8192)

    @model_validator(mode="after")
    def validate_expectations(self) -> "RecoveryRecallCase":
        expected = set(self.expected_memory_ids)
        if len(expected) != len(self.expected_memory_ids):
            raise ValueError("expected_memory_ids must be unique")
        if not set(self.expected_version_ids).issubset(expected):
            raise ValueError(
                "expected_version_ids keys must also appear in expected_memory_ids"
            )
        return self


class RecoveryRecallCorpus(StrictModel):
    corpus_id: str = Field(min_length=1, max_length=200)
    cases: list[RecoveryRecallCase] = Field(min_length=1, max_length=500)
    require_exact_vector_case: bool = True


class RecoveryRecallCaseResult(StrictModel):
    case_id: str
    expected_count: int = Field(ge=1)
    matched_count: int = Field(ge=0)
    recall_at_k: float = Field(ge=0, le=1)
    passed: bool
    error_code: str | None = None


class RecoveryRecallReport(StrictModel):
    corpus_id: str
    case_count: int = Field(ge=1)
    exact_vector_case_count: int = Field(ge=0)
    expected_count: int = Field(ge=1)
    matched_count: int = Field(ge=0)
    recall_at_k: float = Field(ge=0, le=1)
    cases: list[RecoveryRecallCaseResult]
    errors: list[str] = Field(default_factory=list)
    passed: bool


class RecallSearchProvider(Protocol):
    async def search(self, query: MemorySearchQuery) -> Sequence[MemoryHit]: ...


class RecoveryValidationReport(StrictModel):
    checked_at: datetime
    database_revision: str
    expected_database_revision: str | None = None
    table_counts: dict[str, int]
    invalid_current_versions: int = Field(ge=0)
    invalid_active_items: int = Field(ge=0)
    embedding_hash_mismatches: int = Field(ge=0)
    audit_replay_mismatches: int = Field(ge=0)
    fixed_recall: RecoveryRecallReport | None = None
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


async def validate_fixed_recall(
    provider: RecallSearchProvider,
    corpus: RecoveryRecallCorpus,
) -> RecoveryRecallReport:
    results: list[RecoveryRecallCaseResult] = []
    total_expected = 0
    total_matched = 0
    for case in corpus.cases:
        expected = set(case.expected_memory_ids)
        total_expected += len(expected)
        matched = 0
        error_code = None
        try:
            hits = await provider.search(
                MemorySearchQuery(
                    tenant_id=case.tenant_id,
                    user_id=case.user_id,
                    query=case.query,
                    canonical_key=case.canonical_key,
                    limit=case.limit,
                    memory_types=case.memory_types,
                    scopes=case.scopes,
                    modes=case.modes,
                    token_budget=case.token_budget,
                )
            )
            returned = {hit.memory_id: hit.version_id for hit in hits}
            matched = sum(
                memory_id in returned
                and (
                    memory_id not in case.expected_version_ids
                    or returned[memory_id]
                    == case.expected_version_ids[memory_id]
                )
                for memory_id in expected
            )
        except Exception as exc:
            error_code = type(exc).__name__
        recall = matched / len(expected)
        passed = error_code is None and recall >= case.minimum_recall_at_k
        total_matched += matched
        results.append(
            RecoveryRecallCaseResult(
                case_id=case.case_id,
                expected_count=len(expected),
                matched_count=matched,
                recall_at_k=recall,
                passed=passed,
                error_code=error_code,
            )
        )
    aggregate_recall = total_matched / total_expected
    exact_vector_case_count = sum(
        SearchMode.EXACT_VECTOR in case.modes for case in corpus.cases
    )
    errors = []
    if corpus.require_exact_vector_case and exact_vector_case_count == 0:
        errors.append("exact_vector_case_missing")
    return RecoveryRecallReport(
        corpus_id=corpus.corpus_id,
        case_count=len(results),
        exact_vector_case_count=exact_vector_case_count,
        expected_count=total_expected,
        matched_count=total_matched,
        recall_at_k=aggregate_recall,
        cases=results,
        errors=errors,
        passed=not errors and all(result.passed for result in results),
    )


async def validate_memory_recovery(
    connection: AsyncConnection,
    *,
    expected_database_revision: str | None = None,
    fixed_recall: RecoveryRecallReport | None = None,
    require_fixed_recall: bool = False,
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
    if require_fixed_recall and fixed_recall is None:
        errors.append("fixed_recall_missing")
    elif fixed_recall is not None and not fixed_recall.passed:
        errors.append("fixed_recall_failed")
    return RecoveryValidationReport(
        checked_at=datetime.now().astimezone(),
        database_revision=revision,
        expected_database_revision=expected_database_revision,
        table_counts=counts,
        invalid_current_versions=invalid_current_versions,
        invalid_active_items=invalid_active_items,
        embedding_hash_mismatches=embedding_hash_mismatches,
        audit_replay_mismatches=audit_replay_mismatches,
        fixed_recall=fixed_recall,
        errors=errors,
        passed=not errors,
    )
