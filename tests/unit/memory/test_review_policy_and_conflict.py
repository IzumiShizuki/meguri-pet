from datetime import datetime, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.conflict_resolver import (
    ConflictResolver,
    canonical_key,
)
from services.meguri_core.memory_service.enums import (
    ActorType,
    ConflictAction,
    MemoryScope,
    MemoryStatus,
    MemoryType,
)
from services.meguri_core.memory_service.models import (
    MemoryCandidateCreate,
    MemoryItem,
    MemoryVersion,
)
from services.meguri_core.memory_service.review_policy import CandidateReviewPolicy


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


def candidate(text="User prefers unsweetened tea", **overrides):
    values = {
        "tenant_id": "meguri-dev",
        "user_id": "user-a",
        "memory_type": "user_preference",
        "content_text": text,
        "content_json": {"subject": "user", "predicate": "preferred_drink", "value": "tea"},
        "confidence": 0.95,
        "source_client_id": "website",
        "source_session_id": "session-web",
        "source_turn_id": "turn-1",
        "source_kind": "direct_user",
    }
    values.update(overrides)
    return MemoryCandidateCreate(**values)


def item(text="User prefers unsweetened tea", *, key=None):
    memory_id = uuid4()
    version = MemoryVersion(
        version_id=uuid4(),
        memory_id=memory_id,
        version_no=1,
        content_text=text,
        change_reason="candidate approved",
        provenance={"source_turn_id": "turn-0"},
        created_by_type=ActorType.USER,
        created_at=NOW,
    )
    return MemoryItem(
        memory_id=memory_id,
        tenant_id="meguri-dev",
        user_id="user-a",
        memory_type=MemoryType.USER_PREFERENCE,
        scope=MemoryScope.GLOBAL_USER,
        status=MemoryStatus.ACTIVE,
        canonical_key=key,
        current_version_id=version.version_id,
        importance=0.7,
        confidence=0.9,
        created_at=NOW,
        updated_at=NOW,
        current_version=version,
    )


@pytest.mark.parametrize(
    "text",
    [
        "My API key is abc-123",
        "密码是 123456",
        "Screenshot OCR raw screenshot: secret",
        "I am probably diagnosed with something",
        "今天心情有点差",
    ],
)
def test_policy_rejects_credentials_raw_sources_sensitive_inference_and_transient_state(text):
    assert CandidateReviewPolicy().evaluate(candidate(text)).rejected


def test_auto_approval_is_disabled_by_default_and_strict_when_enabled():
    assert CandidateReviewPolicy().evaluate(candidate()).disposition == "queue"
    enabled = CandidateReviewPolicy(auto_approve_enabled=True)
    assert enabled.evaluate(candidate()).auto_approved
    assert enabled.evaluate(candidate(source_kind="llm_candidate")).disposition == "queue"


def test_structured_conflict_supersedes_but_exact_content_deduplicates():
    first = candidate()
    existing = item(key=canonical_key(first))
    duplicate = ConflictResolver().resolve(first, [existing])
    assert duplicate.action is ConflictAction.DUPLICATE

    correction = candidate(
        "User now prefers black coffee",
        content_json={"subject": "user", "predicate": "preferred_drink", "value": "coffee"},
    )
    conflict = ConflictResolver().resolve(correction, [existing])
    assert conflict.action is ConflictAction.SUPERSEDE
    assert conflict.existing_memory_id == existing.memory_id


def test_semantic_similarity_deduplicates_when_lexical_overlap_is_low():
    proposed = candidate(
        "Tea without sugar is the user's preferred drink",
        content_json={},
    )
    existing = item("The user chooses unsweetened tea")
    resolution = ConflictResolver().resolve(
        proposed,
        [existing],
        semantic_scores={existing.memory_id: 1.0000001},
    )

    assert resolution.action is ConflictAction.DUPLICATE
    assert resolution.reason == "high_semantic_similarity"
    assert resolution.similarity == 1.0
