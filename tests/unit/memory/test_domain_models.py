from datetime import datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from services.meguri_core.memory_service.contracts import AuthoritativeMemoryProvider
from services.meguri_core.memory_service.enums import (
    CandidateStatus,
    MemoryStatus,
    MemoryVersionStatus,
    candidate_transition_allowed,
    memory_transition_allowed,
    version_transition_allowed,
)
from services.meguri_core.memory_service.models import (
    MemoryCandidateCreate,
    MemorySearchQuery,
    MemoryUpdate,
)


def candidate_payload(**overrides):
    values = {
        "tenant_id": "meguri-dev",
        "user_id": "user-a",
        "memory_type": "user_preference",
        "content_text": "User prefers unsweetened tea",
        "confidence": 0.9,
        "source_client_id": "website",
        "source_session_id": "session-web",
        "source_turn_id": "turn-1",
    }
    values.update(overrides)
    return values


def test_unknown_domain_enums_are_rejected():
    with pytest.raises(ValidationError):
        MemoryCandidateCreate(**candidate_payload(memory_type="invented_type"))
    with pytest.raises(ValidationError):
        MemoryCandidateCreate(**candidate_payload(sensitivity="secretish"))
    with pytest.raises(ValidationError):
        MemoryCandidateCreate(**candidate_payload(risk_level="unknown"))
    with pytest.raises(ValidationError):
        MemoryCandidateCreate(**candidate_payload(merge_policy="overwrite"))


def test_domain_models_forbid_unknown_fields():
    with pytest.raises(ValidationError):
        MemoryCandidateCreate(**candidate_payload(database_row={"private": True}))


@pytest.mark.parametrize(
    "content_json",
    [
        {"relationship_stage": "lover"},
        {"nested": {"relationship-state": "lover"}},
        {"changes": [{"relationship score": 99}]},
        {"relationshipStage": "lover"},
    ],
)
def test_candidates_cannot_modify_protected_relationship_fields(content_json):
    with pytest.raises(ValidationError, match="protected field"):
        MemoryCandidateCreate(**candidate_payload(content_json=content_json))


def test_generic_memory_update_has_no_relationship_stage_escape_hatch():
    with pytest.raises(ValidationError):
        MemoryUpdate(
            tenant_id="meguri-dev",
            user_id="user-a",
            content_text="User prefers coffee",
            change_reason="user correction",
            relationship_stage="lover",
        )
    with pytest.raises(ValidationError, match="protected field"):
        MemoryUpdate(
            tenant_id="meguri-dev",
            user_id="user-a",
            content_text="User prefers coffee",
            content_json={"nested": {"relationshipStage": "lover"}},
            change_reason="user correction",
        )


def test_search_requires_exact_embedding_dimension():
    with pytest.raises(ValidationError):
        MemorySearchQuery(
            tenant_id="meguri-dev",
            user_id="user-a",
            query="tea",
            query_embedding=[0.1] * 3,
        )
    query = MemorySearchQuery(
        tenant_id="meguri-dev",
        user_id="user-a",
        query="tea",
        query_embedding=[0.0] * 2048,
    )
    assert len(query.query_embedding or []) == 2048


def test_updates_require_timezone_aware_dates():
    with pytest.raises(ValidationError):
        MemoryUpdate(
            tenant_id="meguri-dev",
            user_id="user-a",
            content_text="User prefers coffee",
            change_reason="user correction",
            effective_at=datetime(2026, 7, 14, 12, 0),
        )


def test_state_machine_rejects_terminal_candidate_rewrites():
    assert candidate_transition_allowed(
        CandidateStatus.PENDING_REVIEW, CandidateStatus.PROCESSING
    )
    assert candidate_transition_allowed(
        CandidateStatus.PROCESSING, CandidateStatus.APPROVED
    )
    assert not candidate_transition_allowed(
        CandidateStatus.APPROVED, CandidateStatus.PENDING_REVIEW
    )
    assert memory_transition_allowed(MemoryStatus.ACTIVE, MemoryStatus.DELETED)
    assert memory_transition_allowed(MemoryStatus.DELETED, MemoryStatus.ACTIVE)
    assert not memory_transition_allowed(MemoryStatus.SUPERSEDED, MemoryStatus.ACTIVE)
    assert version_transition_allowed(
        MemoryVersionStatus.ACTIVE, MemoryVersionStatus.SUPERSEDED
    )
    assert not version_transition_allowed(
        MemoryVersionStatus.SUPERSEDED, MemoryVersionStatus.ACTIVE
    )


def test_authoritative_contract_is_runtime_checkable():
    class IncompleteProvider:
        async def get(self, memory_id=uuid4(), **_):
            return memory_id

    assert not isinstance(IncompleteProvider(), AuthoritativeMemoryProvider)
