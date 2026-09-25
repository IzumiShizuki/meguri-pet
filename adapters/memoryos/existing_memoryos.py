from __future__ import annotations

import hashlib
import hmac
import json
from typing import Any
from urllib.parse import quote, urlparse

import httpx

from services.meguri_core.memory import (
    MemoryExtractionInput,
    MemoryHit,
    MemoryRecord,
    MemorySearchInput,
    MemoryType,
    MemoryUpsertInput,
    SessionSummary,
    SessionSummaryInput,
)
from services.meguri_core.schemas import MemoryCandidate


class MemoryOSCompatibilityError(RuntimeError):
    pass


class MemoryOSUnsupportedOperation(MemoryOSCompatibilityError):
    pass


class ExistingMemoryOSAdapter:
    """Adapter for the existing shizuki-site MemoryOS Flask wrapper.

    The wrapper has no stable record IDs, update API or delete API. Therefore
    this adapter is suitable for shadow evaluation only and deliberately raises
    for operations that cannot be represented safely.
    """

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8788",
        *,
        scope_salt: str,
        timeout_seconds: float = 8.0,
        auth_token: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        allow_non_loopback: bool = False,
    ) -> None:
        if not scope_salt:
            raise ValueError("scope_salt must not be empty")
        normalized_url = base_url.rstrip("/")
        host = urlparse(normalized_url).hostname
        if host in {"0.0.0.0", "::"}:
            raise ValueError("MemoryOS must not use a wildcard client address")
        if not allow_non_loopback and host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("Existing MemoryOS requires loopback unless explicitly allowed")
        self.base_url = normalized_url
        self.scope_salt = scope_salt.encode("utf-8")
        self.timeout = httpx.Timeout(timeout_seconds)
        self.auth_token = auth_token
        self.transport = transport

    @property
    def authentication_mode(self) -> str:
        return "bearer" if self.auth_token else "none"

    def scope_id_for_user(self, user_id: str) -> str:
        digest = hmac.new(self.scope_salt, user_id.encode("utf-8"), hashlib.sha256).hexdigest()[:32]
        return f"meguri-{digest}"

    async def health(self) -> dict[str, Any]:
        return await self._request("GET", "/health")

    async def extract_candidates(self, input: MemoryExtractionInput) -> list[MemoryCandidate]:
        # Candidate extraction belongs to the Meguri companion policy. Calling
        # MemoryOS /respond here would invoke its internal LLM and is forbidden.
        return []

    async def search(self, input: MemorySearchInput) -> list[MemoryHit]:
        scope_id = self.scope_id_for_user(input.user_id)
        data = await self._request(
            "POST",
            self._scope_path(scope_id, "retrieve"),
            json={"query": input.query, "max_results": input.limit, "journal_limit": 0},
        )
        hits: list[MemoryHit] = []
        episodic = data.get("episodic")
        if isinstance(episodic, list):
            for index, item in enumerate(episodic):
                if not isinstance(item, dict):
                    continue
                user_input = str(item.get("user_input") or "").strip()
                assistant_response = str(item.get("assistant_response") or "").strip()
                canonical = "\n".join(value for value in (user_input, assistant_response) if value)
                if canonical:
                    hits.append(self._hit(input.user_id, scope_id, "episodic", canonical, item, index))
        summary = data.get("summary")
        if isinstance(summary, dict):
            knowledge = summary.get("retrieved_user_knowledge")
            if isinstance(knowledge, list):
                for index, item in enumerate(knowledge):
                    if isinstance(item, dict) and str(item.get("knowledge") or "").strip():
                        hits.append(
                            self._hit(
                                input.user_id,
                                scope_id,
                                "core_profile",
                                str(item["knowledge"]).strip(),
                                item,
                                index + len(hits),
                            )
                        )
        hits.sort(key=lambda hit: hit.score, reverse=True)
        return hits[: input.limit]

    async def upsert(self, input: MemoryUpsertInput) -> MemoryRecord:
        raise MemoryOSUnsupportedOperation(
            "existing MemoryOS is a read-only import/shadow source; writes are forbidden"
        )

    async def supersede(self, old_id: str, next: MemoryUpsertInput) -> MemoryRecord:
        raise MemoryOSUnsupportedOperation(
            "existing MemoryOS has no update/version API; supersede is unsupported"
        )

    async def delete(self, memory_id: str) -> None:
        raise MemoryOSUnsupportedOperation(
            "existing MemoryOS has no delete API; deletion is unsupported"
        )

    async def summarize_session(self, input: SessionSummaryInput) -> SessionSummary:
        # Local deterministic summary avoids the MemoryOS /respond LLM route.
        content = " ".join(message.content.strip() for message in input.messages if message.content.strip())
        return SessionSummary(
            user_id=input.user_id,
            client_id=input.client_id,
            session_id=input.session_id,
            summary=content[:1000],
            message_count=len(input.messages),
        )

    async def list_records(self, user_id: str, include_deleted: bool = False) -> list[MemoryRecord]:
        scope_id = self.scope_id_for_user(user_id)
        data = await self._request(
            "GET",
            self._scope_path(scope_id, "journal"),
            params={"limit": 1000},
        )
        entries = data.get("entries")
        if not isinstance(entries, list):
            return []
        records: list[MemoryRecord] = []
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            canonical = str(entry.get("assistant_response") or "").strip()
            timestamp = str(entry.get("timestamp") or "")
            if not canonical:
                continue
            meta = entry.get("meta_data") if isinstance(entry.get("meta_data"), dict) else {}
            memory_type = str(meta.get("memory_type") or "episodic")
            if memory_type not in {
                "core_profile", "preference", "episodic", "relationship", "shared_experience",
                "promise", "important_person", "ongoing_project", "recent_emotion", "session_summary",
            }:
                memory_type = "episodic"
            records.append(
                MemoryRecord(
                    memory_id=self._synthetic_id(scope_id, canonical, timestamp),
                    user_id=user_id,
                    memory_type=memory_type,
                    canonical_text=canonical,
                    source_client=str(meta.get("source_client") or "memoryos"),
                    source_session=str(meta.get("source_session") or scope_id),
                    confidence=float(meta.get("confidence") or 0.5),
                    sensitivity="normal",
                    importance=int(meta.get("importance") or 3),
                )
            )
        return records

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        headers = dict(kwargs.pop("headers", {}))
        if self.auth_token:
            headers["Authorization"] = f"Bearer {self.auth_token}"
        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout,
                transport=self.transport,
                headers=headers,
            ) as client:
                response = await client.request(method, path, **kwargs)
                response.raise_for_status()
        except (httpx.HTTPError, httpx.TimeoutException) as exc:
            raise MemoryOSCompatibilityError("MemoryOS request failed") from exc
        try:
            payload = response.json()
        except ValueError as exc:
            raise MemoryOSCompatibilityError("MemoryOS returned invalid JSON") from exc
        if not isinstance(payload, dict):
            raise MemoryOSCompatibilityError("MemoryOS response must be an object")
        if payload.get("success") is not True:
            raise MemoryOSCompatibilityError(str(payload.get("message") or "MemoryOS request failed"))
        data = payload.get("data")
        if not isinstance(data, dict):
            raise MemoryOSCompatibilityError("MemoryOS response data must be an object")
        return data

    def _scope_path(self, scope_id: str, operation: str) -> str:
        return f"/api/v1/memory/sessions/{quote(scope_id, safe='')}/{operation}"

    def _synthetic_id(self, scope_id: str, content: str, timestamp: str) -> str:
        digest = hashlib.sha256(
            json.dumps([scope_id, content, timestamp], ensure_ascii=False).encode("utf-8")
        ).hexdigest()[:24]
        return f"memoryos-{digest}"

    def _hit(
        self,
        user_id: str,
        scope_id: str,
        memory_type: MemoryType,
        canonical: str,
        source: dict[str, Any],
        index: int,
    ) -> MemoryHit:
        timestamp = str(source.get("timestamp") or "")
        record = MemoryRecord(
            memory_id=self._synthetic_id(scope_id, canonical, timestamp),
            user_id=user_id,
            memory_type=memory_type,
            canonical_text=canonical,
            source_client="memoryos",
            source_session=scope_id,
            confidence=max(0.5, 0.9 - index * 0.05),
            sensitivity="normal",
            importance=3,
        )
        return MemoryHit(score=max(0.4, 0.9 - index * 0.05), record=record)
