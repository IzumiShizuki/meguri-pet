from __future__ import annotations

"""Loopback-only compatibility bridge for the first Java runtime phase.

The Java runtime owns turn orchestration in phase one, while this module keeps
the existing Python memory provider authoritative.  The bridge is deliberately
disabled unless an internal token is configured; it is not a public memory API.
"""

import hmac
import os
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from .memory import (
    CompanionMemoryPolicy,
    MemoryExtractionInput,
    MemorySearchInput,
    MemoryUpsertInput,
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
    if client_host not in allowed_hosts:
        raise HTTPException(status_code=403, detail="memory bridge is loopback-only")
    expected = os.getenv("MEGURI_INTERNAL_BRIDGE_TOKEN", "").strip()
    supplied = request.headers.get("X-Meguri-Internal-Token", "")
    if not expected or not supplied or not hmac.compare_digest(supplied, expected):
        raise HTTPException(status_code=503, detail="memory bridge is not enabled")


def _provider(request: Request):
    app = request.app
    orchestrator = getattr(app.state, "orchestrator", None)
    if orchestrator is None:
        raise HTTPException(status_code=503, detail="memory provider is unavailable")
    return orchestrator.memory


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
        if (
            authoritative_candidate is None
            and decision.status == "accepted"
            and decision.upsert is not None
        ):
            written = await provider.upsert(decision.upsert)
            written_ids.append(written.memory_id)
        events.append(
            {
                "candidate": decision.candidate.model_dump(mode="json"),
                "review_status": decision.status,
                "reason": decision.reason,
                "authoritative_candidate_id": (
                    str(authoritative_candidate.candidate_id)
                    if authoritative_candidate is not None
                    else None
                ),
            }
        )
    pending = authoritative_pending or any(
        decision.status == "pending_review" for decision in decisions
    )
    return {
        "status": "pending" if pending else "written",
        "written_ids": written_ids,
        "candidate_ids": candidate_ids,
        "decisions": [decision.status for decision in decisions],
        "events": events,
    }
