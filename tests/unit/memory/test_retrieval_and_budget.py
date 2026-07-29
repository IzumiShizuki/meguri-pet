from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.enums import (
    ActorType,
    MemoryScope,
    MemoryStatus,
    MemoryType,
    MemoryVersionStatus,
)
from services.meguri_core.memory_service.models import MemoryItem, MemoryVersion
from services.meguri_core.memory_service.retrieval import (
    RetrievalWeights,
    build_hit,
    rerank_and_budget,
    weighted_rrf_hits,
)
from services.meguri_core.memory_service.token_budget import estimate_tokens


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


def item(
    text="User prefers unsweetened tea",
    *,
    updated_at=NOW,
    effective_at=None,
    expires_at=None,
    deleted_at=None,
    version_status=MemoryVersionStatus.ACTIVE,
):
    memory_id = uuid4()
    version = MemoryVersion(
        version_id=uuid4(),
        memory_id=memory_id,
        version_no=1,
        status=version_status,
        content_text=text,
        change_reason="candidate approved",
        provenance={"source_turn_id": "turn-0"},
        created_by_type=ActorType.USER,
        created_at=updated_at,
    )
    return MemoryItem(
        memory_id=memory_id,
        tenant_id="meguri-dev",
        user_id="user-a",
        memory_type=MemoryType.USER_PREFERENCE,
        scope=MemoryScope.GLOBAL_USER,
        status=MemoryStatus.ACTIVE,
        current_version_id=version.version_id,
        importance=0.7,
        confidence=0.9,
        effective_at=effective_at,
        expires_at=expires_at,
        created_at=updated_at,
        updated_at=updated_at,
        deleted_at=deleted_at,
        current_version=version,
    )


def test_token_budget_and_reranking_are_deterministic():
    recent = item(updated_at=NOW)
    old = item(text="User works on a long running project", updated_at=NOW - timedelta(days=720))
    recent_hit = build_hit(recent, semantic=0.8, keyword=0.5, now=NOW)
    old_hit = build_hit(old, semantic=0.8, keyword=0.5, now=NOW)
    assert recent_hit.score > old_hit.score
    assert rerank_and_budget([old_hit, recent_hit, recent_hit], token_budget=100, limit=10) == [recent_hit, old_hit]
    assert rerank_and_budget([recent_hit], token_budget=1, limit=10) == []
    assert estimate_tokens("中文 tea preference") >= 4


def test_retrieval_weights_must_sum_to_one():
    with pytest.raises(ValueError):
        RetrievalWeights(semantic=1.0)


def test_weighted_rrf_uses_channel_rank_and_excludes_non_active_items():
    semantic_winner = item("semantic winner")
    keyword_winner = item("keyword winner")
    hits = weighted_rrf_hits(
        [(semantic_winner, 0.99, 0.01), (keyword_winner, 0.01, 0.99)], now=NOW
    )
    assert {hit.memory_id for hit in hits} == {
        semantic_winner.memory_id, keyword_winner.memory_id
    }
    assert all(0 <= hit.score <= 1 for hit in hits)
    conflicted = keyword_winner.model_copy(update={"status": MemoryStatus.CONFLICTED})
    filtered = weighted_rrf_hits([(conflicted, 1, 1)], now=NOW)
    assert filtered == []


@pytest.mark.parametrize(
    "blocked",
    [
        item(effective_at=NOW + timedelta(seconds=1)),
        item(expires_at=NOW),
        item(deleted_at=NOW),
        item(version_status=MemoryVersionStatus.TOMBSTONE),
    ],
)
def test_retrieval_defense_in_depth_excludes_ineligible_versions(blocked):
    assert weighted_rrf_hits([(blocked, 1.0, 1.0)], now=NOW) == []
    with pytest.raises(ValueError, match="active, effective"):
        build_hit(blocked, semantic=1.0, keyword=1.0, now=NOW)


def test_weighted_rrf_depends_on_channel_ranks_not_raw_score_magnitudes():
    first, second = item("first"), item("second")
    original = weighted_rrf_hits(
        [(first, 0.99, 0.20), (second, 0.70, 0.90)], now=NOW
    )
    rescaled = weighted_rrf_hits(
        [(first, 0.51, 0.01), (second, 0.50, 0.02)], now=NOW
    )
    assert {hit.memory_id: hit.score for hit in original} == {
        hit.memory_id: hit.score for hit in rescaled
    }
