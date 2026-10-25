from __future__ import annotations

"""Trusted Java desktop bridge; browser identity/actors never reach this router."""

import hashlib
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Request, Response
from pydantic import Field

from .api_auth import api_error
from .memory import SessionMessage
from .memory_api import memory_call
from .memory_bridge import _authorize, _provider
from .memory_service.contracts import AuthoritativeMemoryProvider
from .memory_service.enums import ActorType
from .memory_service.export import render_memory_export_jsonl
from .memory_service.models import CandidateReview, MemoryActor, MemoryUpdate, SessionSummaryUpsert, StrictModel
from .schemas import MemoryCandidate as RuntimeMemoryCandidate

router = APIRouter(prefix="/internal/memory/desktop", tags=["desktop-memory-bridge"])


class DesktopScope(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    client_id: Literal["desktop_pet"]
    session_id: str = Field(min_length=1, max_length=200)


class DesktopMutation(DesktopScope):
    request_id: str = Field(min_length=1, max_length=200)


class DesktopReview(DesktopMutation):
    candidate_id: UUID
    decision: Literal["approve", "reject"]


class DesktopItemMutation(DesktopMutation):
    memory_id: UUID


class DesktopItemQuery(DesktopScope):
    memory_id: UUID


class DesktopCorrection(DesktopItemMutation):
    base_version_id: UUID
    content_text: str = Field(min_length=1, max_length=4000)
    change_reason: str = Field(min_length=1, max_length=1000)


class DesktopSummary(DesktopMutation):
    checkpoint: int = Field(ge=1)
    messages: list[SessionMessage] = Field(min_length=2, max_length=20)
    structured_candidates: list[RuntimeMemoryCandidate] = Field(default_factory=list, max_length=3)


async def scoped_provider(request: Request, scope: DesktopScope):
    await _authorize(request)
    provider = _provider(request)
    if getattr(provider, "provider_name", "") != "native_pgvector" or not isinstance(
        provider, AuthoritativeMemoryProvider
    ):
        raise api_error(503, "authoritative_provider_required", "正式记忆需要 native_pgvector 服务。")
    if getattr(provider, "tenant_id", None) != scope.tenant_id:
        raise api_error(403, "tenant_scope_mismatch", "记忆服务与桌面身份的租户不一致。")
    return provider


def desktop_actor(scope: DesktopScope) -> MemoryActor:
    return MemoryActor(actor_type=ActorType.USER, actor_id=f"desktop:{scope.user_id}")


@router.post("/status")
async def desktop_status(scope: DesktopScope, request: Request):
    provider = await scoped_provider(request, scope)
    await memory_call(provider.health())
    return {"available": True, "provider": "native_pgvector", "reason": "ready",
            "capabilities": ["memory_detail", "memory_corrections"]}


@router.post("/records")
async def desktop_records(scope: DesktopScope, request: Request):
    provider = await scoped_provider(request, scope)
    records = await memory_call(provider.list_records(scope.user_id, include_deleted=True))
    # Return display fields only; raw provenance and other users are excluded.
    return {"items": [record.model_dump(mode="json") for record in records]}


@router.post("/candidates")
async def desktop_candidates(scope: DesktopScope, request: Request):
    provider = await scoped_provider(request, scope)
    candidates = await memory_call(provider.list_candidates(tenant_id=scope.tenant_id, user_id=scope.user_id))
    return {"items": [candidate.model_dump(mode="json", exclude={"provenance", "content_json"}) for candidate in candidates]}


@router.post("/detail")
async def desktop_detail(body: DesktopItemQuery, request: Request):
    provider = await scoped_provider(request, body)
    item = await memory_call(provider.get(body.memory_id, tenant_id=body.tenant_id, user_id=body.user_id))
    version = item.current_version
    provenance = version.provenance if version else {}
    # Never return raw provenance, structured content or actor identifiers.
    def source(key: str, limit: int):
        value = provenance.get(key)
        return value[:limit] if isinstance(value, str) and value.strip() else None

    return {
        "memory_id": str(item.memory_id), "memory_type": item.memory_type,
        "status": item.status, "updated_at": item.updated_at,
        "current_version_id": str(item.current_version_id),
        "content_text": version.content_text if version else "",
        "version_no": version.version_no if version else None,
        "change_reason": version.change_reason if version else None,
        "source": {"client_id": source("source_client_id", 100),
                   "session_id": source("source_session_id", 200),
                   "kind": source("source_kind", 80)},
        "can_correct": item.status.value == "active" and version is not None,
    }


@router.post("/correct")
async def desktop_correct(body: DesktopCorrection, request: Request):
    provider = await scoped_provider(request, body)
    # Scope and actor come from the trusted Core, never the mutation body.
    candidate = await memory_call(provider.propose_supersede(
        body.memory_id,
        MemoryUpdate(tenant_id=body.tenant_id, user_id=body.user_id,
                     base_version_id=body.base_version_id, content_text=body.content_text,
                     change_reason=body.change_reason,
                     provenance={"source_client_id": body.client_id,
                                 "source_session_id": body.session_id, "source_kind": "direct_user"}),
        actor=desktop_actor(body), source_client_id=body.client_id,
        source_session_id=body.session_id,
        source_turn_id="desktop-correction:" + hashlib.sha256(body.request_id.encode()).hexdigest()[:32],
        request_id=body.request_id,
    ))
    return {"candidate": candidate.model_dump(mode="json", exclude={"provenance", "content_json"})}


@router.post("/review")
async def desktop_review(body: DesktopReview, request: Request):
    provider = await scoped_provider(request, body)
    candidates = await memory_call(provider.list_candidates(tenant_id=body.tenant_id, user_id=body.user_id))
    if not any(candidate.candidate_id == body.candidate_id for candidate in candidates):
        raise api_error(404, "candidate_not_found", "未找到属于当前用户的候选记忆。")
    item = await memory_call(provider.review_candidate(
        body.candidate_id,
        CandidateReview(decision=body.decision, reason="desktop owner review"),
        actor=desktop_actor(body), request_id=body.request_id,
    ))
    return {"item": item.model_dump(mode="json") if item else None}


@router.post("/delete")
async def desktop_delete(body: DesktopItemMutation, request: Request):
    provider = await scoped_provider(request, body)
    # Check ownership before any mutation even for compatibility providers.
    await memory_call(provider.get(body.memory_id, tenant_id=body.tenant_id, user_id=body.user_id))
    await memory_call(provider.delete(body.memory_id, tenant_id=body.tenant_id, user_id=body.user_id,
        reason="desktop owner soft deletion", actor=desktop_actor(body), request_id=body.request_id))
    return {"status": "deleted"}


@router.post("/restore")
async def desktop_restore(body: DesktopItemMutation, request: Request):
    provider = await scoped_provider(request, body)
    await memory_call(provider.get(body.memory_id, tenant_id=body.tenant_id, user_id=body.user_id))
    item = await memory_call(provider.restore(body.memory_id, tenant_id=body.tenant_id,
        user_id=body.user_id, actor=desktop_actor(body), request_id=body.request_id))
    return {"item": item}


@router.post("/export")
async def desktop_export(body: DesktopMutation, request: Request):
    provider = await scoped_provider(request, body)
    exported = await memory_call(provider.export_user(body.user_id, tenant_id=body.tenant_id,
        format="jsonl", request_id=body.request_id))
    return Response(content=render_memory_export_jsonl(exported), media_type="application/x-ndjson")


@router.post("/summary")
async def desktop_summary(body: DesktopSummary, request: Request):
    provider = await scoped_provider(request, body)
    # The stable checkpoint, not model output, defines this durable operation.
    key = hashlib.sha256("\0".join((body.tenant_id, body.user_id, body.client_id,
        body.session_id, str(body.checkpoint))).encode()).hexdigest()[:32]
    result = await memory_call(provider.summarize_session(SessionSummaryUpsert(
        tenant_id=body.tenant_id, user_id=body.user_id, client_id=body.client_id,
        session_id=body.session_id,
        summary_text="\n".join(f"{message.role}: {message.content}" for message in body.messages)[:10000],
        summary_json={"message_count": len(body.messages), "structured_candidates": [
            candidate.model_dump(mode="json") for candidate in body.structured_candidates]},
        source_range={"checkpoint": body.checkpoint, "message_count": len(body.messages)},
    ), request_id=f"desktop-summary:{key}"))
    # Read the persisted first result on retries; changing extraction output
    # cannot produce a second set of candidates for this checkpoint.
    candidate_ids = []
    for index, value in enumerate(result.summary_json.get("structured_candidates", [])):
        candidate = RuntimeMemoryCandidate.model_validate(value)
        created = await memory_call(provider.submit_runtime_candidate(candidate,
            user_id=body.user_id, source_client=body.client_id, source_session=body.session_id,
            source_turn_id=f"desktop-summary:{body.checkpoint}", request_id=f"desktop-summary:{key}:{index}"))
        candidate_ids.append(str(created.candidate_id))
    return {"status": "persisted", "summary": result.summary_text,
        "message_count": result.summary_json.get("message_count", len(body.messages)),
        "candidate_ids": candidate_ids, "checkpoint": body.checkpoint}
