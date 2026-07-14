from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.enums import (
    ActorType,
    MemoryScope,
    MemoryStatus,
    MemoryType,
)
from services.meguri_core.memory_service.models import MemoryItem, MemoryVersion
from services.meguri_core.memory_service.retrieval import (
    RetrievalWeights,
    build_hit,
    rerank_and_budget,
)
from services.meguri_core.memory_service.token_budget import estimate_tokens


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


def item(text="User prefers unsweetened tea", *, updated_at=NOW):
    memory_id = uuid4()
    version = MemoryVersion(
        version_id=uuid4(),
        memory_id=memory_id,
        version_no=1,
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
        created_at=updated_at,
        updated_at=updated_at,
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
