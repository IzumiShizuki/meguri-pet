from types import SimpleNamespace
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.embedding import (
    BgeM3EmbeddingProvider,
    EmbeddingWorker,
    content_sha256,
)


@pytest.mark.asyncio
async def test_bge_m3_adapter_requires_revision_and_dimension():
    with pytest.raises(ValueError):
        BgeM3EmbeddingProvider(revision="main", embed_callable=lambda _: [])
    wrong = BgeM3EmbeddingProvider(
        revision="0123456789abcdef", embed_callable=lambda _: [[0.0] * 3]
    )
    with pytest.raises(ValueError):
        await wrong.embed(["text"])
    valid = BgeM3EmbeddingProvider(
        revision="0123456789abcdef", embed_callable=lambda _: [[0.0] * 1024]
    )
    assert len((await valid.embed(["text"]))[0]) == 1024
    assert len(content_sha256("text")) == 64


class WorkerRepository:
    def __init__(self):
        self.task = SimpleNamespace(
            outbox_id=uuid4(),
            payload={"version_id": str(uuid4())},
            attempts=0,
        )
        self.claimed = False
        self.completed = []
        self.failed = []
        self.saved = []

    async def claim_outbox(self, **_):
        if self.claimed:
            return []
        self.claimed = True
        return [self.task]

    async def get_version(self, _):
        return SimpleNamespace(content_text="User prefers tea")

    async def save_embedding(self, **kwargs):
        self.saved.append(kwargs)

    async def complete_outbox(self, outbox_id):
        self.completed.append(outbox_id)

    async def fail_outbox(self, outbox_id, **kwargs):
        self.failed.append((outbox_id, kwargs))


class WorkerUow:
    def __init__(self, repository):
        self.repository = repository

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None


class WorkerUowFactory:
    def __init__(self, repository):
        self.repository = repository

    def __call__(self):
        return WorkerUow(self.repository)


@pytest.mark.asyncio
async def test_embedding_worker_completes_or_schedules_retry():
    repository = WorkerRepository()
    provider = BgeM3EmbeddingProvider(
        revision="0123456789abcdef", embed_callable=lambda _: [[0.0] * 1024]
    )
    worker = EmbeddingWorker(
        WorkerUowFactory(repository),  # type: ignore[arg-type]
        provider,
        worker_id="worker-1",
    )
    assert await worker.run_once() == {"claimed": 1, "completed": 1, "failed": 0}
    assert repository.saved[0]["content_sha256"] == content_sha256("User prefers tea")
    assert repository.completed == [repository.task.outbox_id]

    failed_repository = WorkerRepository()

    async def unavailable(_):
        raise ConnectionError("secret endpoint detail")

    failed_provider = BgeM3EmbeddingProvider(
        revision="0123456789abcdef", embed_callable=unavailable
    )
    failed_worker = EmbeddingWorker(
        WorkerUowFactory(failed_repository),  # type: ignore[arg-type]
        failed_provider,
        worker_id="worker-2",
    )
    assert await failed_worker.run_once() == {
        "claimed": 1,
        "completed": 0,
        "failed": 1,
    }
    assert failed_repository.failed[0][1]["error_code"] == "ConnectionError"
