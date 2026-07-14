from __future__ import annotations

from typing import Any
from uuid import UUID

from fastapi import APIRouter
from pydantic import Field

from .api_auth import (
    PrincipalDependency,
    ProviderDependency,
    RequestIdDependency,
    api_error,
    require_admin,
)
from .memory_service.contracts import (
    MemoryAuthorizationError,
    MemoryConflictError,
    MemoryNotFoundError,
    MemoryStateError,
    MemoryUnavailableError,
)
from .memory_service.enums import (
    CandidateStatus,
    MemoryScope,
    MemoryType,
    SearchMode,
    Sensitivity,
    SourceKind,
)
from .memory_service.metrics import memory_metrics
from .memory_service.models import (
    CandidateReview,
    MemoryCandidateCreate,
    MemorySearchQuery,
    MemoryUpdate,
    StrictModel,
)


router = APIRouter(prefix="/v1", tags=["authoritative-memory"])


class CandidateCreateRequest(StrictModel):
    memory_type: MemoryType
    content_text: str = Field(min_length=1, max_length=4000)
    content_json: dict[str, Any] = Field(default_factory=dict)
    confidence: float = Field(ge=0, le=1)
    sensitivity: Sensitivity = Sensitivity.NORMAL
    source_session_id: str = Field(min_length=1, max_length=200)
    source_turn_id: str = Field(min_length=1, max_length=200)
    source_message_ids: list[str] = Field(default_factory=list, max_length=50)
    extraction_model: str | None = Field(default=None, max_length=300)
    extraction_prompt_hash: str | None = Field(default=None, max_length=64)
    provenance: dict[str, Any] = Field(default_factory=dict)


class ReviewRequest(StrictModel):
    reason: str = Field(min_length=1, max_length=1000)


class SearchRequest(StrictModel):
    query: str = Field(min_length=1, max_length=4000)
    limit: int = Field(default=5, ge=1, le=50)
    memory_types: list[MemoryType] = Field(default_factory=list)
    scopes: list[MemoryScope] = Field(default_factory=lambda: [MemoryScope.GLOBAL_USER])
    modes: list[SearchMode] = Field(default_factory=lambda: [SearchMode.HYBRID])
    token_budget: int = Field(default=1200, ge=64, le=8192)
    query_embedding: list[float] | None = Field(
        default=None, min_length=1024, max_length=1024
    )
    embedding_model: str | None = None
    embedding_revision: str | None = None


class SupersedeRequest(StrictModel):
    content_text: str = Field(min_length=1, max_length=4000)
    content_json: dict[str, Any] = Field(default_factory=dict)
    change_reason: str = Field(min_length=1, max_length=1000)
    confidence: float | None = Field(default=None, ge=0, le=1)
    importance: float | None = Field(default=None, ge=0, le=1)
    provenance: dict[str, Any] = Field(default_factory=dict)


class ExportRequest(StrictModel):
    format: str = "jsonl"


async def memory_call(awaitable):
    try:
        return await awaitable
    except MemoryNotFoundError:
        raise api_error(404, "memory_not_found", "memory resource was not found") from None
    except (MemoryConflictError, MemoryStateError):
        raise api_error(409, "memory_state_conflict", "memory state transition was rejected") from None
    except MemoryAuthorizationError:
        raise api_error(403, "memory_forbidden", "memory access was denied") from None
    except MemoryUnavailableError:
        memory_metrics.inc("memory_provider_failure_total")
        raise api_error(503, "memory_unavailable", "memory service is temporarily unavailable") from None
    except ValueError:
        raise api_error(422, "memory_validation_failed", "memory request was rejected") from None
    except Exception:
        memory_metrics.inc("memory_provider_failure_total")
        raise api_error(503, "memory_unavailable", "memory service is temporarily unavailable") from None


def require_formal_memory(principal) -> None:
    if not principal.formal_memory_allowed:
        raise api_error(403, "verified_binding_required", "verified identity binding is required")


@router.post("/memory/candidates", status_code=201)
async def create_candidate(
    body: CandidateCreateRequest,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    require_formal_memory(principal)
    source_kind = (
        SourceKind.DIRECT_USER
        if principal.actor_type.value == "user"
        else SourceKind.LLM_CANDIDATE
    )
    return await memory_call(
        provider.create_candidate(
            MemoryCandidateCreate(
                tenant_id=principal.tenant_id,
                user_id=principal.user_id,
                memory_type=body.memory_type,
                content_text=body.content_text,
                content_json=body.content_json,
                confidence=body.confidence,
                sensitivity=body.sensitivity,
                source_client_id=principal.client_id,
                source_session_id=body.source_session_id,
                source_turn_id=body.source_turn_id,
                source_message_ids=body.source_message_ids,
                source_kind=source_kind,
                extraction_model=body.extraction_model,
                extraction_prompt_hash=body.extraction_prompt_hash,
                provenance=body.provenance,
            ),
            request_id=request_id,
        )
    )


@router.get("/memory/candidates")
async def list_candidates(
    principal: PrincipalDependency,
    provider: ProviderDependency,
    status: CandidateStatus | None = None,
):
    require_formal_memory(principal)
    return {
        "items": await memory_call(
            provider.list_candidates(
                tenant_id=principal.tenant_id,
                user_id=principal.user_id,
                status=status.value if status is not None else None,
            )
        )
    }


async def review_candidate(
    candidate_id: UUID,
    body: ReviewRequest,
    principal,
    provider,
    request_id: str,
    decision: str,
):
    require_admin(principal)
    item = await memory_call(
        provider.review_candidate(
            candidate_id,
            CandidateReview(decision=decision, reason=body.reason),
            actor=principal.memory_actor(),
            request_id=request_id,
        )
    )
    return {"item": item}


@router.post("/memory/candidates/{candidate_id}/approve")
async def approve_candidate(
    candidate_id: UUID,
    body: ReviewRequest,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    return await review_candidate(
        candidate_id, body, principal, provider, request_id, "approve"
    )


@router.post("/memory/candidates/{candidate_id}/reject")
async def reject_candidate(
    candidate_id: UUID,
    body: ReviewRequest,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    return await review_candidate(
        candidate_id, body, principal, provider, request_id, "reject"
    )


@router.post("/memories/search")
async def search_memories(
    body: SearchRequest,
    principal: PrincipalDependency,
    provider: ProviderDependency,
):
    require_formal_memory(principal)
    hits = await memory_call(
        provider.search(
            MemorySearchQuery(
                tenant_id=principal.tenant_id,
                user_id=principal.user_id,
                **body.model_dump(),
            )
        )
    )
    return {"items": hits}


@router.get("/memories/{memory_id}")
async def get_memory(
    memory_id: UUID,
    principal: PrincipalDependency,
    provider: ProviderDependency,
):
    require_formal_memory(principal)
    return await memory_call(
        provider.get(
            memory_id,
            tenant_id=principal.tenant_id,
            user_id=principal.user_id,
        )
    )


@router.post("/memories/{memory_id}/supersede")
async def supersede_memory(
    memory_id: UUID,
    body: SupersedeRequest,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    require_formal_memory(principal)
    return await memory_call(
        provider.supersede(
            memory_id,
            MemoryUpdate(
                tenant_id=principal.tenant_id,
                user_id=principal.user_id,
                **body.model_dump(),
            ),
            actor=principal.memory_actor(),
            request_id=request_id,
        )
    )


@router.post("/memories/{memory_id}/restore")
async def restore_memory(
    memory_id: UUID,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    require_formal_memory(principal)
    return await memory_call(
        provider.restore(
            memory_id,
            tenant_id=principal.tenant_id,
            user_id=principal.user_id,
            actor=principal.memory_actor(),
            request_id=request_id,
        )
    )


@router.post("/memories/export")
async def export_memories(
    body: ExportRequest,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    require_formal_memory(principal)
    return await memory_call(
        provider.export_user(
            principal.user_id,
            tenant_id=principal.tenant_id,
            format=body.format,
            request_id=request_id,
        )
    )
