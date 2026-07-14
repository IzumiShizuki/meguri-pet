from __future__ import annotations

from services.meguri_core.memory_service.benchmark import (
    run_synthetic_exact_ann_benchmark,
)
from services.meguri_core.memory_service.recovery import replay_audit_states


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
