from __future__ import annotations

import hashlib
import inspect
import re
from collections.abc import Awaitable, Callable
from time import perf_counter
from typing import Protocol

from pydantic import Field

from ..contracts import AuthoritativeMemoryProvider
from ..models import MemorySearchQuery, StrictModel


class Mem0ShadowHit(StrictModel):
    shadow_id: str
    content_text: str
    score: float = Field(ge=0, le=1)


class Mem0SearchSidecar(Protocol):
    async def search(
        self, *, tenant_id: str, user_id: str, query: str, limit: int
    ) -> list[Mem0ShadowHit]: ...


class ShadowEvaluation(StrictModel):
    status: str
    query_hash: str
    authoritative_count: int = Field(ge=0)
    shadow_count: int = Field(ge=0)
    overlap_count: int = Field(ge=0)
    overlap_at_k: float = Field(ge=0, le=1)
    authoritative_latency_ms: float = Field(ge=0)
    shadow_latency_ms: float = Field(ge=0)
    error_code: str | None = None


EvaluationSink = Callable[[ShadowEvaluation], None | Awaitable[None]]


def _content_fingerprint(text: str) -> str:
    normalized = re.sub(r"\s+", " ", text).strip().casefold()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


class Mem0ShadowEvaluator:
    """Compare recall offline; shadow hits are never returned as prompt memories."""

    def __init__(
        self,
        authoritative: AuthoritativeMemoryProvider,
        sidecar: Mem0SearchSidecar | None,
        *,
        enabled: bool = False,
        sink: EvaluationSink | None = None,
    ) -> None:
        self.authoritative = authoritative
        self.sidecar = sidecar
        self.enabled = enabled
        self.sink = sink

    async def evaluate(self, query: MemorySearchQuery) -> ShadowEvaluation:
        authoritative_started = perf_counter()
        authoritative_hits = await self.authoritative.search(query)
        authoritative_latency = (perf_counter() - authoritative_started) * 1000
        if not self.enabled or self.sidecar is None:
            result = ShadowEvaluation(
                status="disabled",
                query_hash=_content_fingerprint(query.query),
                authoritative_count=len(authoritative_hits),
                shadow_count=0,
                overlap_count=0,
                overlap_at_k=0,
                authoritative_latency_ms=authoritative_latency,
                shadow_latency_ms=0,
            )
            await self._emit(result)
            return result

        shadow_started = perf_counter()
        try:
            shadow_hits = await self.sidecar.search(
                tenant_id=query.tenant_id,
                user_id=query.user_id,
                query=query.query,
                limit=query.limit,
            )
            status = "ok"
            error_code = None
        except Exception:
            shadow_hits = []
            status = "shadow_unavailable"
            error_code = "sidecar_failure"
        shadow_latency = (perf_counter() - shadow_started) * 1000

        authoritative_fingerprints = {
            _content_fingerprint(hit.content_text) for hit in authoritative_hits
        }
        shadow_fingerprints = {
            _content_fingerprint(hit.content_text) for hit in shadow_hits
        }
        overlap = len(authoritative_fingerprints & shadow_fingerprints)
        denominator = max(1, min(query.limit, len(authoritative_fingerprints)))
        result = ShadowEvaluation(
            status=status,
            query_hash=_content_fingerprint(query.query),
            authoritative_count=len(authoritative_hits),
            shadow_count=len(shadow_hits),
            overlap_count=overlap,
            overlap_at_k=overlap / denominator,
            authoritative_latency_ms=authoritative_latency,
            shadow_latency_ms=shadow_latency,
            error_code=error_code,
        )
        await self._emit(result)
        return result

    async def _emit(self, result: ShadowEvaluation) -> None:
        if self.sink is None:
            return
        returned = self.sink(result)
        if inspect.isawaitable(returned):
            await returned
