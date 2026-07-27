"""Provider-managed reranking for Meguri retrieval paths."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import math
from typing import Sequence

import httpx

from ..secrets import SecretConfigurationError, read_secret
from .contracts import MemoryUnavailableError


@dataclass(frozen=True)
class RerankResult:
    index: int
    relevance_score: float


class DashScopeRerankProvider:
    """HTTPS adapter for Model Studio's qwen3-rerank endpoint."""

    def __init__(
        self,
        *,
        endpoint: str,
        api_key: str,
        model: str = "qwen3-rerank",
        timeout_seconds: float = 12.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        parsed = httpx.URL(endpoint)
        loopback = parsed.host in {"127.0.0.1", "localhost", "::1"}
        if parsed.scheme not in {"http", "https"} or not parsed.host:
            raise ValueError("MEGURI_DASHSCOPE_RERANK_ENDPOINT must be an HTTP(S) URL")
        if parsed.scheme != "https" and not loopback:
            raise ValueError("remote rerank endpoints must use HTTPS")
        if not api_key.strip() or not model.strip():
            raise ValueError("DashScope rerank API key and model are required")
        if timeout_seconds <= 0:
            raise ValueError("DashScope rerank timeout must be positive")
        self.endpoint = endpoint
        self.api_key = api_key
        self.model = model.strip()
        self.timeout = httpx.Timeout(timeout_seconds)
        self.transport = transport

    async def rerank(
        self,
        query: str,
        documents: Sequence[str],
        *,
        top_n: int,
    ) -> list[RerankResult]:
        texts = [str(document) for document in documents]
        if not query.strip() or not texts or top_n <= 0:
            return []
        if any(not value.strip() for value in texts):
            raise ValueError("rerank documents must not be empty")
        payload = {
            "model": self.model,
            "input": {"query": query, "documents": texts},
            "parameters": {"top_n": min(top_n, len(texts)), "return_documents": False},
        }
        response: httpx.Response | None = None
        failure: Exception | None = None
        for attempt in range(3):
            try:
                async with httpx.AsyncClient(
                    timeout=self.timeout,
                    transport=self.transport,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                ) as client:
                    response = await client.post(self.endpoint, json=payload)
                if response.status_code == 429 or response.status_code >= 500:
                    failure = httpx.HTTPStatusError(
                        "retryable rerank API response", request=response.request, response=response
                    )
                else:
                    response.raise_for_status()
                    break
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code < 500 and exc.response.status_code != 429:
                    raise MemoryUnavailableError("rerank API rejected the request") from exc
                failure = exc
            except (httpx.TimeoutException, httpx.TransportError) as exc:
                failure = exc
            if attempt < 2:
                await asyncio.sleep(0.25 * (attempt + 1))
        else:
            if isinstance(failure, httpx.TimeoutException):
                raise MemoryUnavailableError("rerank API timed out") from failure
            raise MemoryUnavailableError("rerank API request failed") from failure
        assert response is not None
        try:
            body = response.json()
            rows = body.get("output", {}).get("results", body.get("results", []))
            results = [
                RerankResult(index=int(item["index"]), relevance_score=float(item["relevance_score"]))
                for item in rows
            ]
        except (AttributeError, KeyError, TypeError, ValueError) as exc:
            raise MemoryUnavailableError("rerank API returned an invalid response") from exc
        if any(
            result.index < 0
            or result.index >= len(texts)
            or not math.isfinite(result.relevance_score)
            for result in results
        ):
            raise MemoryUnavailableError("rerank API returned invalid result indexes")
        # A duplicate index could otherwise make a response look like it
        # contains more independent evidence than it actually does.
        if len({result.index for result in results}) != len(results):
            raise MemoryUnavailableError("rerank API returned duplicate result indexes")
        return results


def create_runtime_rerank_provider(
    *,
    env: dict[str, str] | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> DashScopeRerankProvider | None:
    import os

    values = os.environ if env is None else env
    backend = values.get("MEGURI_RERANK_BACKEND", "disabled").strip().casefold()
    if backend in {"", "none", "disabled"}:
        return None
    if backend != "dashscope":
        raise RuntimeError("MEGURI_RERANK_BACKEND must be dashscope or disabled")
    try:
        timeout = float(values.get("MEGURI_RERANK_TIMEOUT_SECONDS", "12"))
        api_key = read_secret(values, "MEGURI_DASHSCOPE_API_KEY", required=True)
    except (ValueError, SecretConfigurationError) as exc:
        raise RuntimeError(str(exc)) from exc
    return DashScopeRerankProvider(
        endpoint=values.get(
            "MEGURI_DASHSCOPE_RERANK_ENDPOINT",
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
        ),
        api_key=api_key or "",
        model=values.get("MEGURI_RERANK_MODEL", "qwen3-rerank"),
        timeout_seconds=timeout,
        transport=transport,
    )
