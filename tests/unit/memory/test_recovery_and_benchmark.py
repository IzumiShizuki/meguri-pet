from __future__ import annotations

from types import SimpleNamespace
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.benchmark import (
    run_synthetic_exact_ann_benchmark,
)
from services.meguri_core.memory_service.enums import SearchMode
from services.meguri_core.memory_service.recovery import (
    RecoveryRecallCase,
    RecoveryRecallCorpus,
    replay_audit_states,
    validate_fixed_recall,
)


def test_audit_replay_reconstructs_visibility_and_hard_delete() -> None:
    states = replay_audit_states(
        [
            {"aggregate_id": "memory-a", "action": "item_create"},
            {"aggregate_id": "memory-a", "action": "supersede"},
            {"aggregate_id": "memory-a", "action": "delete"},
            {"aggregate_id": "memory-a", "action": "restore"},
            {"aggregate_id": "memory-b", "action": "item_create"},
            {"aggregate_id": "memory-b", "action": "hard_delete"},
        ]
    )

    assert states == {"memory-a": "active", "memory-b": "hard_deleted"}


def test_synthetic_exact_baseline_is_deterministic_and_ann_stays_disabled() -> None:
    first = run_synthetic_exact_ann_benchmark(
        corpus_size=30,
        query_count=8,
        dimension=64,
        top_k=3,
        seed=42,
    )
    second = run_synthetic_exact_ann_benchmark(
        corpus_size=30,
        query_count=8,
        dimension=64,
        top_k=3,
        seed=42,
    )

    assert first.exact.error_rate == 0
    assert first.exact.recall_at_k == 1
    assert first.ann.status == "not_enabled"
    assert first.ann.recall_at_k is None
    assert first.exact.corpus_size == second.exact.corpus_size
    assert first.exact.query_count == second.exact.query_count


@pytest.mark.asyncio
async def test_fixed_recall_validates_expected_memory_and_current_version() -> None:
    memory_a = uuid4()
    version_a = uuid4()
    memory_b = uuid4()

    class Provider:
        async def search(self, query):
            if query.query == "known preference":
                return [SimpleNamespace(memory_id=memory_a, version_id=version_a)]
            return []

    report = await validate_fixed_recall(
        Provider(),
        RecoveryRecallCorpus(
            corpus_id="restore-gate-v1",
            cases=[
                RecoveryRecallCase(
                    case_id="current-version-is-recalled",
                    tenant_id="tenant-a",
                    user_id="user-a",
                    query="known preference",
                    expected_memory_ids=[memory_a],
                    expected_version_ids={memory_a: version_a},
                    modes=[SearchMode.EXACT_VECTOR],
                    query_embedding=[1.0, *([0.0] * 1023)],
                    embedding_model="BAAI/bge-m3",
                    embedding_revision="5617a9f61b028005a4858fdac845db406aefb181",
                ),
                RecoveryRecallCase(
                    case_id="missing-result-fails",
                    tenant_id="tenant-a",
                    user_id="user-a",
                    query="missing preference",
                    expected_memory_ids=[memory_b],
                ),
            ],
        ),
    )

    assert report.recall_at_k == 0.5
    assert report.exact_vector_case_count == 1
    assert report.cases[0].passed is True
    assert report.cases[1].passed is False
    assert report.passed is False
