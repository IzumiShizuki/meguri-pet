from __future__ import annotations

from datetime import datetime, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.contracts import MemoryNotFoundError
from services.meguri_core.memory_service.metrics import MemoryMetrics
from services.meguri_core.memory_service.models import (
    MemoryFeedback,
    MemoryFeedbackCreate,
)
from services.meguri_core.memory_service.service import MemoryService


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


class FeedbackRepository:
    def __init__(self, *, version_exists=True) -> None:
        self.version_exists = version_exists
        self.idempotency = {}
        self.created = []

    async def get_idempotent(self, tenant_id, operation, request_id):
        return self.idempotency.get((tenant_id, operation, request_id))

    async def put_idempotent(self, tenant_id, operation, request_id, response):
        self.idempotency[(tenant_id, operation, request_id)] = response

    async def create_feedback(self, feedback):
        if not self.version_exists:
            return None
        result = MemoryFeedback(
            **feedback.model_dump(),
            feedback_id=uuid4(),
            created_at=NOW,
        )
        self.created.append(result)
        return result


class Uow:
    def __init__(self, repository):
        self.repository = repository

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None


class UowFactory:
    def __init__(self, repository):
        self.repository = repository

    def __call__(self):
        return Uow(self.repository)


def feedback() -> MemoryFeedbackCreate:
    return MemoryFeedbackCreate(
        tenant_id="tenant-a",
        user_id="user-a",
        memory_id=uuid4(),
        version_id=uuid4(),
        feedback_kind="false_recall",
        query_text="tea",
        hit_rank=1,
    )


@pytest.mark.asyncio
async def test_feedback_is_idempotent_and_updates_unlabelled_metric() -> None:
    repository = FeedbackRepository()
    metrics = MemoryMetrics()
    service = MemoryService(
        UowFactory(repository),  # type: ignore[arg-type]
        metrics=metrics,
    )
    proposed = feedback()

    first = await service.record_feedback(proposed, request_id="feedback-1")
    replayed = await service.record_feedback(proposed, request_id="feedback-1")

    assert first.feedback_id == replayed.feedback_id
    assert len(repository.created) == 1
    assert "memory_false_recall_feedback_total 1" in metrics.render()
    assert "user-a" not in metrics.render()


@pytest.mark.asyncio
async def test_feedback_rejects_version_outside_user_memory_scope() -> None:
    service = MemoryService(
        UowFactory(FeedbackRepository(version_exists=False)),  # type: ignore[arg-type]
    )
    with pytest.raises(MemoryNotFoundError):
        await service.record_feedback(feedback(), request_id="feedback-missing")
