from datetime import datetime, timezone
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
from services.meguri_core.memory_service.models import (
    CandidateReview,
    MemoryActor,
    MemoryCandidate,
    MemoryCandidateCreate,
    MemoryItem,
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
        self.fail_create_item = fail_create_item

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
        return []

    async def create_item(self, candidate, **_):
        if self.fail_create_item:
            raise RuntimeError("forced item failure")
        return item_for(candidate)

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
