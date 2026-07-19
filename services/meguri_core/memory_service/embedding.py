from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Sequence
import hashlib
import inspect
import os
import re
from typing import Any
from uuid import UUID

from .contracts import EmbeddingProvider, MemoryUnavailableError
from .repository import MemoryUnitOfWorkFactory
from .metrics import MemoryMetrics, memory_metrics
from .release import (
    EMBEDDING_DIMENSION,
    EMBEDDING_MODEL,
    EMBEDDING_MODEL_REVISION,
)


EmbedCallable = Callable[[Sequence[str]], list[list[float]] | Awaitable[list[list[float]]]]


class BgeM3EmbeddingProvider:
    model = EMBEDDING_MODEL
    dimension = EMBEDDING_DIMENSION

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


ModelLoader = Callable[..., Any]


def _sentence_transformer_loader(**kwargs: Any) -> Any:
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as exc:
        raise MemoryUnavailableError(
            "sentence-transformers embedding backend is unavailable"
        ) from exc
    return SentenceTransformer(**kwargs)


class SentenceTransformerBgeM3EmbeddingProvider(BgeM3EmbeddingProvider):
    """Lazy pinned BGE-M3 runtime adapter with no implicit model download."""

    def __init__(
        self,
        *,
        revision: str = EMBEDDING_MODEL_REVISION,
        device: str = "cpu",
        cache_folder: str | None = None,
        local_files_only: bool = True,
        model_loader: ModelLoader | None = None,
    ) -> None:
        self.device = device
        self.cache_folder = cache_folder
        self.local_files_only = local_files_only
        self._model_loader = model_loader or _sentence_transformer_loader
        self._model: Any | None = None
        self._load_lock = asyncio.Lock()
        super().__init__(revision=revision, embed_callable=self._embed_runtime)

    async def _load_model(self) -> Any:
        if self._model is not None:
            return self._model
        async with self._load_lock:
            if self._model is None:
                kwargs: dict[str, Any] = {
                    "model_name_or_path": self.model,
                    "revision": self.revision,
                    "device": self.device,
                    "trust_remote_code": False,
                    "local_files_only": self.local_files_only,
                }
                if self.cache_folder:
                    kwargs["cache_folder"] = self.cache_folder
                self._model = await asyncio.to_thread(self._model_loader, **kwargs)
        return self._model

    async def _embed_runtime(self, texts: Sequence[str]) -> list[list[float]]:
        model = await self._load_model()

        def encode() -> list[list[float]]:
            encoded = model.encode(
                list(texts),
                normalize_embeddings=True,
                convert_to_numpy=True,
                show_progress_bar=False,
            )
            if hasattr(encoded, "tolist"):
                encoded = encoded.tolist()
            return [list(vector) for vector in encoded]

        return await asyncio.to_thread(encode)


def create_runtime_embedding_provider(
    *,
    expected_revision: str | None = None,
) -> EmbeddingProvider | None:
    backend = os.getenv(
        "MEGURI_EMBEDDING_BACKEND", "sentence_transformers"
    ).strip().casefold()
    if backend in {"none", "disabled"}:
        return None
    if backend != "sentence_transformers":
        raise RuntimeError(
            "MEGURI_EMBEDDING_BACKEND must be sentence_transformers or disabled"
        )
    revision = os.getenv(
        "MEGURI_EMBEDDING_MODEL_REVISION",
        expected_revision or EMBEDDING_MODEL_REVISION,
    )
    if revision != EMBEDDING_MODEL_REVISION:
        raise RuntimeError(
            "configured embedding revision does not match the release revision"
        )
    return SentenceTransformerBgeM3EmbeddingProvider(
        revision=revision,
        device=os.getenv("MEGURI_EMBEDDING_DEVICE", "cpu"),
        cache_folder=os.getenv("MEGURI_EMBEDDING_CACHE_DIR"),
        local_files_only=os.getenv(
            "MEGURI_EMBEDDING_LOCAL_FILES_ONLY", "true"
        ).lower()
        == "true",
    )


def content_sha256(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


class EmbeddingWorker:
    def __init__(
        self,
        unit_of_work_factory: MemoryUnitOfWorkFactory,
        provider: EmbeddingProvider,
        *,
        worker_id: str,
        batch_size: int = 20,
        lease_seconds: int = 300,
        max_attempts: int = 5,
        base_retry_seconds: int = 30,
        metrics: MemoryMetrics | None = None,
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
        self.metrics = metrics or memory_metrics

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
        self.metrics.set_gauge("memory_embedding_queue_depth", len(claimed))
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
                self.metrics.inc("memory_embedding_failure_total")
        self.metrics.set_gauge("memory_embedding_queue_depth", 0)
        return {"claimed": len(claimed), "completed": completed, "failed": failed}
