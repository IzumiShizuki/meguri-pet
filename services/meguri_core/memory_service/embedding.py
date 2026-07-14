from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence
import hashlib
import inspect
import re
from uuid import UUID

from .repository import MemoryUnitOfWorkFactory


EmbedCallable = Callable[[Sequence[str]], list[list[float]] | Awaitable[list[list[float]]]]


class BgeM3EmbeddingProvider:
    model = "BAAI/bge-m3"
    dimension = 1024

    def __init__(self, *, revision: str, embed_callable: EmbedCallable) -> None:
        if revision.casefold() in {"main", "master", "latest"} or not re.fullmatch(
            r"[0-9a-fA-F]{7,64}", revision
        ):
            raise ValueError("BGE-M3 revision must be an immutable commit hash")
        self.revision = revision
        self._embed_callable = embed_callable

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        result = self._embed_callable(texts)
        if inspect.isawaitable(result):
            result = await result
        vectors = list(result)
        if len(vectors) != len(texts):
            raise ValueError("embedding provider returned the wrong number of vectors")
        if any(len(vector) != self.dimension for vector in vectors):
            raise ValueError("BGE-M3 embedding dimension must be 1024")
        return vectors


def content_sha256(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


class EmbeddingWorker:
    def __init__(
        self,
        unit_of_work_factory: MemoryUnitOfWorkFactory,
        provider: BgeM3EmbeddingProvider,
        *,
        worker_id: str,
        batch_size: int = 20,
        lease_seconds: int = 300,
        max_attempts: int = 5,
        base_retry_seconds: int = 30,
    ) -> None:
        if not worker_id.strip():
            raise ValueError("worker_id must not be empty")
        self.uow_factory = unit_of_work_factory
        self.provider = provider
        self.worker_id = worker_id
        self.batch_size = batch_size
        self.lease_seconds = lease_seconds
        self.max_attempts = max_attempts
        self.base_retry_seconds = base_retry_seconds

    @staticmethod
    def _repository(unit_of_work):
        repository = unit_of_work.repository
        if repository is None:
            raise RuntimeError("memory unit of work is not active")
        return repository

    async def run_once(self) -> dict[str, int]:
        async with self.uow_factory() as uow:
            claimed = await self._repository(uow).claim_outbox(
                worker_id=self.worker_id,
                limit=self.batch_size,
                lease_seconds=self.lease_seconds,
            )
        completed = failed = 0
        for task in claimed:
            try:
                version_id = UUID(str(task.payload["version_id"]))
                async with self.uow_factory() as uow:
                    version = await self._repository(uow).get_version(version_id)
                    if version is None:
                        raise RuntimeError("embedding source version is missing")
                    content = version.content_text
                vector = (await self.provider.embed([content]))[0]
                digest = content_sha256(content)
                async with self.uow_factory() as uow:
                    repository = self._repository(uow)
                    current = await repository.get_version(version_id)
                    if current is None or content_sha256(current.content_text) != digest:
                        raise RuntimeError("embedding source changed before persistence")
                    await repository.save_embedding(
                        version_id=version_id,
                        model=self.provider.model,
                        revision=self.provider.revision,
                        vector=vector,
                        content_sha256=digest,
                    )
                    await repository.complete_outbox(task.outbox_id)
                completed += 1
            except Exception as exc:
                retry_delay = self.base_retry_seconds * (2 ** min(task.attempts, 8))
                async with self.uow_factory() as uow:
                    await self._repository(uow).fail_outbox(
                        task.outbox_id,
                        error_code=type(exc).__name__,
                        max_attempts=self.max_attempts,
                        retry_delay_seconds=retry_delay,
                    )
                failed += 1
        return {"claimed": len(claimed), "completed": completed, "failed": failed}
