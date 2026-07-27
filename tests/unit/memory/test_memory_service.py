from datetime import datetime, timezone
import hashlib
import json
from types import SimpleNamespace
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.enums import (
    ActorType,
    CandidateStatus,
    MemoryScope,
    MemoryStatus,
    MemoryType,
)
from services.meguri_core.memory_service.contracts import MemoryStateError
from services.meguri_core.memory_service.models import (
    CandidateReview,
    MemoryActor,
    MemoryCandidate,
    MemoryCandidateCreate,
    MemoryItem,
    MemoryUpdate,
    MemoryVersion,
)
from services.meguri_core.memory_service.service import MemoryService


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


def candidate_create(text="User prefers tea"):
    return MemoryCandidateCreate(
        tenant_id="meguri-dev",
        user_id="user-a",
        memory_type="user_preference",
        content_text=text,
        confidence=0.95,
        source_client_id="website",
        source_session_id="session-web",
        source_turn_id="turn-1",
        source_kind="direct_user",
    )


def item_for(candidate):
    memory_id = uuid4()
    version = MemoryVersion(
        version_id=uuid4(),
        memory_id=memory_id,
        version_no=1,
        content_text=candidate.content_text,
        change_reason="approved",
        provenance={},
        created_by_type=ActorType.ADMIN,
        created_at=NOW,
    )
    return MemoryItem(
        memory_id=memory_id,
        tenant_id=candidate.tenant_id,
        user_id=candidate.user_id,
        memory_type=MemoryType.USER_PREFERENCE,
        scope=MemoryScope.GLOBAL_USER,
        status=MemoryStatus.ACTIVE,
        current_version_id=version.version_id,
        importance=0.5,
        confidence=candidate.confidence,
        created_at=NOW,
        updated_at=NOW,
        current_version=version,
    )


class FakeRepository:
    def __init__(self, *, fail_create_item=False):
        self.idempotency = {}
        self.candidate = None
        self.audits = []
        self.finish_calls = []
        self.supersede_calls = []
        self.create_item_calls = []
        self.fail_create_item = fail_create_item
        self.item = None

    async def get_idempotent(self, tenant_id, operation, request_id):
        return self.idempotency.get((tenant_id, operation, request_id))

    async def put_idempotent(self, tenant_id, operation, request_id, response):
        self.idempotency[(tenant_id, operation, request_id)] = response

    async def create_candidate(self, candidate, *, status, review_reason=None):
        self.candidate = MemoryCandidate(
            **candidate.model_dump(),
            candidate_id=uuid4(),
            status=status,
            review_reason=review_reason,
            created_at=NOW,
            updated_at=NOW,
        )
        return self.candidate

    async def append_audit(self, **kwargs):
        self.audits.append(kwargs)

    async def get_candidate_for_update(self, candidate_id):
        if self.candidate is None or self.candidate.candidate_id != candidate_id:
            return None
        return SimpleNamespace(**self.candidate.model_dump(mode="json"))

    async def list_active_items(self, **_):
        return [self.item] if self.item is not None else []

    async def get_item(self, memory_id, **_):
        if self.item is None or self.item.memory_id != memory_id:
            return None
        return self.item

    async def create_item(self, candidate, **_):
        self.create_item_calls.append(candidate)
        if self.fail_create_item:
            raise RuntimeError("forced item failure")
        return item_for(candidate)

    async def supersede_item(self, current, update, **kwargs):
        self.supersede_calls.append((current, update, kwargs))
        return current

    async def finish_candidate(self, row, **kwargs):
        self.finish_calls.append(kwargs)
        return self.candidate


class FakeUow:
    def __init__(self, repository):
        self.repository = repository
        self.exit_error = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, *_):
        self.exit_error = exc_type


class FakeUowFactory:
    def __init__(self, repository):
        self.repository = repository
        self.instances = []

    def __call__(self):
        instance = FakeUow(self.repository)
        self.instances.append(instance)
        return instance


@pytest.mark.asyncio
async def test_candidate_creation_is_idempotent_and_never_implicitly_active():
    repository = FakeRepository()
    service = MemoryService(FakeUowFactory(repository))  # type: ignore[arg-type]
    first = await service.create_candidate(candidate_create(), request_id="request-1")
    second = await service.create_candidate(candidate_create(), request_id="request-1")
    assert first.candidate_id == second.candidate_id
    assert first.status is CandidateStatus.PENDING_REVIEW
    assert len([event for event in repository.audits if event["action"].value == "candidate_create"]) == 1

    rejected = await service.create_candidate(
        candidate_create("My API key is abc"), request_id="request-2"
    )
    assert rejected.status is CandidateStatus.REJECTED
    assert rejected.content_text == "[redacted unsafe memory candidate]"


@pytest.mark.asyncio
async def test_rejected_l0_candidate_is_redacted_before_repository_and_audit():
    repository = FakeRepository()
    service = MemoryService(FakeUowFactory(repository))  # type: ignore[arg-type]
    secret = "My API key is sk-do-not-persist"
    proposed = candidate_create(secret).model_copy(
        update={
            "content_json": {"raw_source": secret},
            "provenance": {"raw_excerpt": secret},
        }
    )
    expected_digest = hashlib.sha256(
        json.dumps(
            {
                "content_text": proposed.content_text,
                "content_json": proposed.content_json,
                "provenance": proposed.provenance,
            },
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    ).hexdigest()

    rejected = await service.create_candidate(proposed, request_id="l0-request")

    durable_state = json.dumps(
        {
            "candidate": rejected.model_dump(mode="json"),
            "audits": repository.audits,
            "idempotency": list(repository.idempotency.values()),
        },
        default=str,
        ensure_ascii=False,
    )
    assert rejected.status is CandidateStatus.REJECTED
    assert rejected.content_json == {
        "content_sha256": expected_digest,
        "redacted": True,
        "rejection_reason": "credential_or_high_risk_identifier",
        "risk_class": "l0",
    }
    assert rejected.provenance == rejected.content_json
    assert secret not in durable_state
    with pytest.raises(MemoryStateError):
        await service.review_candidate(
            rejected.candidate_id,
            CandidateReview(decision="approve", reason="unsafe approval attempt"),
            actor=MemoryActor(actor_type="admin", actor_id="admin-a"),
            request_id="l0-review-attempt",
        )
    assert repository.create_item_calls == []
    assert repository.supersede_calls == []


@pytest.mark.asyncio
async def test_credential_hidden_in_structured_content_is_redacted_before_persistence():
    repository = FakeRepository()
    service = MemoryService(FakeUowFactory(repository))  # type: ignore[arg-type]
    secret = "sk-hidden-structured-secret"
    proposed = candidate_create("User shared an account setting").model_copy(
        update={"content_json": {"api_key": secret}}
    )

    rejected = await service.create_candidate(proposed, request_id="structured-l0")

    assert rejected.status is CandidateStatus.REJECTED
    assert rejected.review_reason == "credential_or_high_risk_identifier"
    assert secret not in rejected.model_dump_json()


@pytest.mark.asyncio
async def test_same_request_id_is_scoped_per_user() -> None:
    repository = FakeRepository()
    service = MemoryService(FakeUowFactory(repository))  # type: ignore[arg-type]
    first_input = candidate_create()
    second_input = first_input.model_copy(update={"user_id": "user-b"})

    first = await service.create_candidate(first_input, request_id="shared-request")
    second = await service.create_candidate(second_input, request_id="shared-request")

    assert first.user_id == "user-a"
    assert second.user_id == "user-b"
    assert first.candidate_id != second.candidate_id
    assert len(repository.idempotency) == 2


@pytest.mark.asyncio
async def test_supersede_proposal_creates_pending_candidate_without_mutating_item():
    repository = FakeRepository()
    repository.item = item_for(candidate_create())
    original_version_id = repository.item.current_version_id
    service = MemoryService(FakeUowFactory(repository))  # type: ignore[arg-type]

    proposed = await service.propose_supersede(
        repository.item.memory_id,
        MemoryUpdate(
            tenant_id="meguri-dev",
            user_id="user-a",
            content_text="User now prefers coffee",
            change_reason="explicit correction",
            base_version_id=original_version_id,
        ),
        actor=MemoryActor(actor_type="user", actor_id="user-a"),
        source_client_id="website",
        source_session_id="session-web",
        source_turn_id="turn-correction",
        request_id="supersede-proposal",
    )

    assert proposed.status is CandidateStatus.PENDING_REVIEW
    assert proposed.merge_policy.value == "supersede"
    assert proposed.base_version_id == original_version_id
    assert proposed.provenance["proposed_supersedes_memory_id"] == str(
        repository.item.memory_id
    )
    assert repository.item.current_version_id == original_version_id

    approved = await service.review_candidate(
        proposed.candidate_id,
        CandidateReview(decision="approve", reason="approved correction"),
        actor=MemoryActor(actor_type="admin", actor_id="admin-a"),
        request_id="supersede-approval",
    )

    assert approved is repository.item
    assert len(repository.supersede_calls) == 1
    assert repository.supersede_calls[0][0].memory_id == repository.item.memory_id
    assert repository.supersede_calls[0][1].base_version_id == original_version_id
    assert repository.create_item_calls == []


@pytest.mark.asyncio
async def test_approval_failure_does_not_finalize_candidate_or_idempotency():
    repository = FakeRepository(fail_create_item=True)
    factory = FakeUowFactory(repository)
    service = MemoryService(factory)  # type: ignore[arg-type]
    candidate = await service.create_candidate(candidate_create(), request_id="create-1")
    with pytest.raises(RuntimeError):
        await service.review_candidate(
            candidate.candidate_id,
            CandidateReview(decision="approve", reason="admin approval"),
            actor=MemoryActor(actor_type="admin", actor_id="admin-a"),
            request_id="review-1",
        )
    assert repository.finish_calls == []
    assert not any(key[1].startswith("candidate.review") for key in repository.idempotency)
    assert factory.instances[-1].exit_error is RuntimeError
