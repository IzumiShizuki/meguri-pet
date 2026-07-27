from __future__ import annotations

"""Loopback-only compatibility bridge for the first Java runtime phase.

The Java runtime owns turn orchestration in phase one, while this module keeps
the existing Python memory provider authoritative.  The bridge is deliberately
disabled unless an internal token is configured; it is not a public memory API.
"""

import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from .memory import (
    MemoryExtractionInput,
    MemorySearchInput,
    SessionMessage,
    SessionSummaryInput,
)
from .schemas import MemoryCandidate


router = APIRouter(prefix="/internal/memory", tags=["internal-memory-bridge"])


class BridgeSearchRequest(BaseModel):
    user_id: str = Field(min_length=1)
    query: str = Field(min_length=1)
    limit: int = Field(default=5, ge=1, le=20)
    memory_types: list[str] = Field(default_factory=list)


class BridgeExtractRequest(BaseModel):
    user_id: str = Field(min_length=1)
    content: str = Field(min_length=1)
    source_client: str = Field(min_length=1)
    source_session: str = Field(min_length=1)


class BridgeWriteRequest(BaseModel):
    user_id: str = Field(min_length=1)
    source_client: str = Field(min_length=1)
    source_session: str = Field(min_length=1)
    source_turn_id: str = Field(min_length=1)
    trace_id: str = Field(min_length=1)
    candidates: list[MemoryCandidate] = Field(default_factory=list, max_length=3)


class BridgeSessionSummaryRequest(BaseModel):
    """A bounded session snapshot from the Java online runtime."""

    user_id: str = Field(min_length=1)
    client_id: str = Field(min_length=1)
    session_id: str = Field(min_length=1)
    messages: list[SessionMessage] = Field(min_length=2, max_length=20)
    structured_candidates: list[MemoryCandidate] = Field(default_factory=list, max_length=3)
    write_candidates: bool = False


async def _authorize(request: Request) -> None:
    client_host = request.client.host if request.client else None
    allowed_hosts = {"127.0.0.1", "::1", "localhost"}
    configured_hosts = os.getenv("MEGURI_INTERNAL_BRIDGE_ALLOWED_HOSTS", "")
    allowed_hosts.update(
        value.strip()
        for value in configured_hosts.split(",")
        if value.strip()
    )
    if os.getenv("MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT", "false").lower() == "true":
        allowed_hosts.add("testclient")
    allowed_cidrs = []
    for value in os.getenv("MEGURI_INTERNAL_BRIDGE_ALLOWED_CIDRS", "").split(","):
        if value.strip():
            try:
                allowed_cidrs.append(ipaddress.ip_network(value.strip(), strict=False))
            except ValueError:
                continue
    try:
        client_ip = ipaddress.ip_address(client_host) if client_host else None
    except ValueError:
        client_ip = None
    if client_host not in allowed_hosts and not any(
        client_ip is not None and client_ip in network for network in allowed_cidrs
    ):
        raise HTTPException(status_code=403, detail="memory bridge is loopback-only")
    expected = _bridge_token()
    supplied = request.headers.get("X-Meguri-Internal-Token", "")
    if not expected or not supplied or not hmac.compare_digest(supplied, expected):
        raise HTTPException(status_code=503, detail="memory bridge is not enabled")


def _bridge_token() -> str:
    token_file = os.getenv("MEGURI_INTERNAL_BRIDGE_TOKEN_FILE", "").strip()
    if token_file:
        try:
            path = Path(token_file)
            if path.is_absolute() and path.is_file():
                value = path.read_text(encoding="utf-8").strip()
                if value:
                    return value
        except OSError:
            pass
    return os.getenv("MEGURI_INTERNAL_BRIDGE_TOKEN", "").strip()


def _provider(request: Request):
    app = request.app
    orchestrator = getattr(app.state, "orchestrator", None)
    if orchestrator is None:
        raise HTTPException(status_code=503, detail="memory provider is unavailable")
    return orchestrator.memory


def _event_candidate(decision, authoritative_status: str | None) -> dict[str, Any]:
    payload = decision.candidate.model_dump(mode="json")
    if decision.status != "rejected" and authoritative_status != "rejected":
        return payload
    digest = hashlib.sha256(decision.candidate.summary.encode("utf-8")).hexdigest()
    return {
        **payload,
        "summary": "[redacted unsafe memory candidate]",
        "sensitivity": "sensitive",
        "content_sha256": digest,
    }


@router.get("/health")
async def bridge_health(request: Request) -> dict[str, Any]:
    await _authorize(request)
    provider = _provider(request)
    health = getattr(provider, "health", None)
    if callable(health):
        result = await health()
    else:
        result = {"status": "ok"}
    return {
        "status": "ok",
        "provider": getattr(provider, "provider_name", "unknown"),
        "details": result,
    }


@router.post("/search")
async def bridge_search(body: BridgeSearchRequest, request: Request) -> dict[str, Any]:
    await _authorize(request)
    provider = _provider(request)
    hits = await provider.search(
        MemorySearchInput(
            user_id=body.user_id,
            query=body.query,
            limit=body.limit,
            memory_types=body.memory_types,
        )
    )
    return {"items": [hit.model_dump(mode="json") for hit in hits]}


@router.post("/extract")
async def bridge_extract(body: BridgeExtractRequest, request: Request) -> dict[str, Any]:
    await _authorize(request)
    provider = _provider(request)
    candidates = await provider.extract_candidates(
        MemoryExtractionInput(
            user_id=body.user_id,
            content=body.content,
            source_client=body.source_client,
            source_session=body.source_session,
        )
    )
    return {"items": [candidate.model_dump(mode="json") for candidate in candidates]}


@router.post("/write")
async def bridge_write(body: BridgeWriteRequest, request: Request) -> dict[str, Any]:
    await _authorize(request)
    provider = _provider(request)
    orchestrator = request.app.state.orchestrator
    existing = await provider.list_records(body.user_id)
    decisions = orchestrator.memory_policy.review(
        user_id=body.user_id,
        source_client=body.source_client,
        source_session=body.source_session,
        candidates=list(body.candidates),
        existing=existing,
    )
    submit_runtime_candidate = getattr(provider, "submit_runtime_candidate", None)
    if any(decision.status != "duplicate" for decision in decisions) and not callable(
        submit_runtime_candidate
    ):
        raise HTTPException(
            status_code=503,
            detail="authoritative memory candidate workflow is unavailable",
        )
    written_ids: list[str] = []
    candidate_ids: list[str] = []
    events: list[dict[str, Any]] = []
    authoritative_pending = False
    for index, decision in enumerate(decisions):
        authoritative_candidate = None
        if callable(submit_runtime_candidate) and decision.status != "duplicate":
            authoritative_candidate = await submit_runtime_candidate(
                decision.candidate,
                user_id=body.user_id,
                source_client=body.source_client,
                source_session=body.source_session,
                source_turn_id=body.source_turn_id,
                request_id=f"{body.trace_id}:candidate:{index}",
            )
            candidate_ids.append(str(authoritative_candidate.candidate_id))
            authoritative_pending = authoritative_pending or (
                authoritative_candidate.status.value == "pending_review"
            )
        events.append(
            {
                "candidate": _event_candidate(
                    decision,
                    authoritative_candidate.status.value
                    if authoritative_candidate is not None
                    else None,
                ),
                "review_status": decision.status,
                "reason": decision.reason,
                "authoritative_candidate_id": (
                    str(authoritative_candidate.candidate_id)
                    if authoritative_candidate is not None
                    else None
                ),
            }
        )
    pending = authoritative_pending or bool(candidate_ids)
    return {
        "status": "pending" if pending else "unchanged",
        "written_ids": written_ids,
        "candidate_ids": candidate_ids,
        "decisions": [decision.status for decision in decisions],
        "events": events,
    }


@router.post("/session-summary")
async def bridge_session_summary(
    body: BridgeSessionSummaryRequest, request: Request
) -> dict[str, Any]:
    """Persist an audit-only session summary; it never writes canonical Lore RAG."""

    await _authorize(request)
    provider = _provider(request)
    submit_runtime_candidate = getattr(provider, "submit_runtime_candidate", None)
    if (
        body.write_candidates
        and body.structured_candidates
        and not callable(submit_runtime_candidate)
    ):
        raise HTTPException(
            status_code=503,
            detail="authoritative memory candidate workflow is unavailable",
        )
    summary = await provider.summarize_session(
        SessionSummaryInput(
            user_id=body.user_id,
            client_id=body.client_id,
            session_id=body.session_id,
            messages=body.messages,
            structured_candidates=body.structured_candidates,
        )
    )
    candidate_ids: list[str] = []
    candidate_status = "audit_only"
    if body.write_candidates and body.structured_candidates and callable(submit_runtime_candidate):
        source_turn_id = f"{body.session_id}:sleep-summary"
        for index, candidate in enumerate(body.structured_candidates):
            summary_digest = hashlib.sha256(
                json.dumps(
                    candidate.model_dump(mode="json"),
                    sort_keys=True,
                    ensure_ascii=False,
                ).encode("utf-8")
            ).hexdigest()[:16]
            created = await submit_runtime_candidate(
                candidate,
                user_id=body.user_id,
                source_client=body.client_id,
                source_session=body.session_id,
                source_turn_id=source_turn_id,
                request_id=f"sleep-summary:{body.session_id}:{index}:{summary_digest}",
            )
            candidate_ids.append(str(created.candidate_id))
        candidate_status = "pending_review"
    payload = summary.model_dump(mode="json")
    payload.update({"candidate_ids": candidate_ids, "candidate_status": candidate_status})
    return payload
