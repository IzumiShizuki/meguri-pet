from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol, runtime_checkable
from uuid import UUID

from .models import (
    CandidateReview,
    IdentityBinding,
    IdentityBindingCreate,
    MemoryActor,
    MemoryCandidate,
    MemoryCandidateCreate,
    MemoryExport,
    MemoryHit,
    MemoryItem,
    MemorySearchQuery,
    MemoryUpdate,
    SessionSummaryUpsert,
)


class MemoryServiceError(RuntimeError):
    """Base class for sanitized, provider-independent memory failures."""


class MemoryNotFoundError(MemoryServiceError):
    pass


class MemoryConflictError(MemoryServiceError):
    pass


class MemoryAuthorizationError(MemoryServiceError):
    pass


class MemoryStateError(MemoryServiceError):
    pass


class MemoryUnavailableError(MemoryServiceError):
    pass


@runtime_checkable
class AuthoritativeMemoryProvider(Protocol):
    async def create_candidate(
        self,
        candidate: MemoryCandidateCreate,
        *,
        request_id: str,
    ) -> MemoryCandidate: ...

    async def list_candidates(
        self,
        *,
        tenant_id: str,
        user_id: str,
        status: str | None = None,
    ) -> list[MemoryCandidate]: ...

    async def review_candidate(
        self,
        candidate_id: UUID,
        decision: CandidateReview,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> MemoryItem | None: ...

    async def search(self, query: MemorySearchQuery) -> list[MemoryHit]: ...

    async def get(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
    ) -> MemoryItem: ...

    async def supersede(
        self,
        memory_id: UUID,
        update: MemoryUpdate,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> MemoryItem: ...

    async def delete(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
        reason: str,
        actor: MemoryActor,
        request_id: str,
    ) -> None: ...

    async def restore(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
        actor: MemoryActor,
        request_id: str,
    ) -> MemoryItem: ...

    async def export_user(
        self,
        user_id: str,
        *,
        tenant_id: str,
        format: str,
        request_id: str,
    ) -> MemoryExport: ...

    async def bind_identity(
        self,
        binding: IdentityBindingCreate,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> IdentityBinding: ...

    async def list_identity_bindings(
        self,
        *,
        tenant_id: str,
        user_id: str,
    ) -> list[IdentityBinding]: ...

    async def unbind_identity(
        self,
        binding_id: UUID,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> None: ...

    async def resolve_identity(
        self,
        *,
        tenant_id: str,
        platform: str,
        platform_user_id: str,
    ) -> str | None: ...

    async def summarize_session(
        self,
        summary: SessionSummaryUpsert,
        *,
        request_id: str,
    ) -> SessionSummaryUpsert: ...


@runtime_checkable
class EmbeddingProvider(Protocol):
    model: str
    revision: str
    dimension: int

    async def embed(self, texts: Sequence[str]) -> list[list[float]]: ...
