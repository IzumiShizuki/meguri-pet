from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Sequence
import hashlib
import inspect
import math
import os
import re
from typing import Any
from uuid import UUID

import httpx

from .contracts import EmbeddingProvider, MemoryUnavailableError
from .repository import MemoryUnitOfWorkFactory
from .metrics import MemoryMetrics, memory_metrics
from .release import (
    EMBEDDING_DIMENSION,
    EMBEDDING_MODEL,
    EMBEDDING_MODEL_REVISION,
)
from ..secrets import SecretConfigurationError, read_secret


LEGACY_BGE_M3_MODEL = "BAAI/bge-m3"
LEGACY_BGE_M3_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
LEGACY_BGE_M3_DIMENSION = 1024


EmbedCallable = Callable[[Sequence[str]], list[list[float]] | Awaitable[list[list[float]]]]


class BgeM3EmbeddingProvider:
    model = LEGACY_BGE_M3_MODEL
    dimension = LEGACY_BGE_M3_DIMENSION

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


class DashScopeEmbeddingProvider:
    """HTTPS adapter for Model Studio's OpenAI-compatible embeddings API.

    The provider deliberately owns no model weights.  It validates every
    response before pgvector sees it, preventing mixed dimensions or malformed
    provider responses from becoming durable memory state.
    """

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        revision: str,
        dimension: int,
        timeout_seconds: float = 20.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        parsed = httpx.URL(base_url)
        loopback = parsed.host in {"127.0.0.1", "localhost", "::1"}
        if parsed.scheme not in {"http", "https"} or not parsed.host:
            raise ValueError("MEGURI_DASHSCOPE_EMBEDDING_BASE_URL must be an HTTP(S) URL")
        if parsed.scheme != "https" and not loopback:
            raise ValueError("remote embedding endpoints must use HTTPS")
        if not api_key.strip():
            raise ValueError("DashScope embedding API key must not be empty")
        if not model.strip() or not revision.strip():
            raise ValueError("DashScope embedding model and revision are required")
        if dimension <= 0:
            raise ValueError("DashScope embedding dimension must be positive")
        if timeout_seconds <= 0:
            raise ValueError("DashScope embedding timeout must be positive")
        self.base_url = base_url.rstrip("/") + "/"
        self.api_key = api_key
        self.model = model.strip()
        self.revision = revision.strip()
        self.dimension = dimension
        self.timeout = httpx.Timeout(timeout_seconds)
        self.transport = transport

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        values = [str(text) for text in texts]
        if not values:
            return []
        if any(not value.strip() for value in values):
            raise ValueError("embedding texts must not be empty")
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self.model,
            "input": values,
            "dimensions": self.dimension,
        }
        response: httpx.Response | None = None
        failure: Exception | None = None
        for attempt in range(3):
            try:
                async with httpx.AsyncClient(
                    base_url=self.base_url,
                    headers=headers,
                    timeout=self.timeout,
                    transport=self.transport,
                ) as client:
                    response = await client.post("embeddings", json=payload)
                if response.status_code == 429 or response.status_code >= 500:
                    failure = httpx.HTTPStatusError(
                        "retryable embedding API response", request=response.request, response=response
                    )
                else:
                    response.raise_for_status()
                    break
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code < 500 and exc.response.status_code != 429:
                    raise MemoryUnavailableError("embedding API rejected the request") from exc
                failure = exc
            except (httpx.TimeoutException, httpx.TransportError) as exc:
                failure = exc
            if attempt < 2:
                await asyncio.sleep(0.25 * (attempt + 1))
        else:
            if isinstance(failure, httpx.TimeoutException):
                raise MemoryUnavailableError("embedding API timed out") from failure
            raise MemoryUnavailableError("embedding API request failed") from failure
        assert response is not None
        try:
            payload = response.json()
            rows = payload["data"]
            ordered = sorted(rows, key=lambda item: int(item.get("index", 0)))
            vectors = [list(item["embedding"]) for item in ordered]
        except (KeyError, TypeError, ValueError) as exc:
            raise MemoryUnavailableError("embedding API returned an invalid response") from exc
        if len(vectors) != len(values):
            raise MemoryUnavailableError("embedding API returned the wrong number of vectors")
        for vector in vectors:
            if len(vector) != self.dimension:
                raise MemoryUnavailableError("embedding API returned an unexpected dimension")
            if any(not isinstance(item, int | float) or not math.isfinite(float(item)) for item in vector):
                raise MemoryUnavailableError("embedding API returned an invalid vector")
        return [[float(item) for item in vector] for vector in vectors]


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
        revision: str = LEGACY_BGE_M3_REVISION,
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
    env: dict[str, str] | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> EmbeddingProvider | None:
    values = os.environ if env is None else env
    backend = values.get("MEGURI_EMBEDDING_BACKEND", "sentence_transformers").strip().casefold()
    if backend in {"none", "disabled"}:
        return None
    revision = values.get(
        "MEGURI_EMBEDDING_MODEL_REVISION", expected_revision or EMBEDDING_MODEL_REVISION
    ).strip()
    if expected_revision and revision != expected_revision:
        raise RuntimeError("configured embedding revision does not match the release revision")
    if backend == "sentence_transformers":
        if revision != LEGACY_BGE_M3_REVISION:
            raise RuntimeError("local BGE-M3 backend requires its pinned BGE-M3 revision")
        return SentenceTransformerBgeM3EmbeddingProvider(
            revision=revision,
            device=values.get("MEGURI_EMBEDDING_DEVICE", "cpu"),
            cache_folder=values.get("MEGURI_EMBEDDING_CACHE_DIR"),
            local_files_only=values.get("MEGURI_EMBEDDING_LOCAL_FILES_ONLY", "true").lower()
            == "true",
        )
    if backend != "dashscope":
        raise RuntimeError(
            "MEGURI_EMBEDDING_BACKEND must be dashscope, sentence_transformers or disabled"
        )
    model = values.get("MEGURI_EMBEDDING_MODEL", EMBEDDING_MODEL).strip()
    try:
        dimension = int(values.get("MEGURI_EMBEDDING_DIMENSION", str(EMBEDDING_DIMENSION)))
        timeout = float(values.get("MEGURI_EMBEDDING_TIMEOUT_SECONDS", "20"))
    except ValueError as exc:
        raise RuntimeError("DashScope embedding dimension and timeout must be numeric") from exc
    try:
        api_key = read_secret(values, "MEGURI_DASHSCOPE_API_KEY", required=True)
    except SecretConfigurationError as exc:
        raise RuntimeError(str(exc)) from exc
    return DashScopeEmbeddingProvider(
        base_url=values.get(
            "MEGURI_DASHSCOPE_EMBEDDING_BASE_URL",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ),
        api_key=api_key or "",
        model=model,
        revision=revision,
        dimension=dimension,
        timeout_seconds=timeout,
        transport=transport,
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
                event_types=("embedding.requested", "embedding.deleted"),
            )
        completed = failed = 0
        self.metrics.set_gauge("memory_embedding_queue_depth", len(claimed))
        for task in claimed:
            try:
                version_id = UUID(str(task.payload["version_id"]))
                if getattr(task, "event_type", "embedding.requested") == "embedding.deleted":
                    deleted_version_id = UUID(str(task.payload["deleted_version_id"]))
                    async with self.uow_factory() as uow:
                        repository = self._repository(uow)
                        await repository.delete_embedding_projection(deleted_version_id)
                        acknowledged = await repository.complete_outbox(
                            task.outbox_id,
                            worker_id=self.worker_id,
                            claim_locked_at=task.locked_at,
                        )
                    completed += int(acknowledged)
                    failed += int(not acknowledged)
                    continue
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
                    acknowledged = await repository.complete_outbox(
                        task.outbox_id,
                        worker_id=self.worker_id,
                        claim_locked_at=task.locked_at,
                    )
                completed += int(acknowledged)
                failed += int(not acknowledged)
            except Exception as exc:
                retry_delay = self.base_retry_seconds * (2 ** min(task.attempts, 8))
                async with self.uow_factory() as uow:
                    await self._repository(uow).fail_outbox(
                        task.outbox_id,
                        worker_id=self.worker_id,
                        claim_locked_at=task.locked_at,
                        error_code=type(exc).__name__,
                        max_attempts=self.max_attempts,
                        retry_delay_seconds=retry_delay,
                    )
                failed += 1
                self.metrics.inc("memory_embedding_failure_total")
        self.metrics.set_gauge("memory_embedding_queue_depth", 0)
        return {"claimed": len(claimed), "completed": completed, "failed": failed}
