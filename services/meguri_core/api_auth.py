from __future__ import annotations

import os
from typing import Annotated

from fastapi import Depends, Header, HTTPException, Request

from .memory_service.contracts import AuthoritativeMemoryProvider
from .memory_service.enums import ActorType
from .memory_service.identity import ResolvedIdentity
from .memory_service.models import MemoryActor, StrictModel


def api_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})


class ApiPrincipal(StrictModel):
    tenant_id: str
    user_id: str
    client_id: str
    session_id: str | None = None
    actor_type: ActorType = ActorType.USER
    actor_id: str
    formal_memory_allowed: bool = True

    def memory_actor(self) -> MemoryActor:
        return MemoryActor(actor_type=self.actor_type, actor_id=self.actor_id)

    @classmethod
    def from_resolved_identity(
        cls,
        identity: ResolvedIdentity,
        *,
        actor_id: str | None = None,
    ) -> "ApiPrincipal":
        return cls(
            tenant_id=identity.tenant_id,
            user_id=identity.user_id,
            client_id=identity.client_id,
            session_id=identity.session_id,
            actor_id=actor_id or identity.user_id,
            formal_memory_allowed=identity.formal_memory_allowed,
        )


async def get_api_principal(request: Request) -> ApiPrincipal:
    injected = getattr(request.state, "meguri_principal", None)
    if isinstance(injected, ApiPrincipal):
        return injected
    if injected is not None:
        return ApiPrincipal.model_validate(injected)
    if os.getenv("MEGURI_ALLOW_TRUSTED_IDENTITY_HEADERS", "false").lower() != "true":
        raise api_error(401, "authentication_required", "authenticated identity is required")
    required_headers = {
        "tenant_id": request.headers.get("X-Meguri-Tenant-ID"),
        "user_id": request.headers.get("X-Meguri-User-ID"),
        "client_id": request.headers.get("X-Meguri-Client-ID"),
        "actor_id": request.headers.get("X-Meguri-Actor-ID"),
    }
    if not all(required_headers.values()):
        raise api_error(401, "identity_headers_missing", "trusted identity headers are incomplete")
    return ApiPrincipal(
        **required_headers,
        actor_type=request.headers.get("X-Meguri-Actor-Type", "user"),
        formal_memory_allowed=request.headers.get(
            "X-Meguri-Formal-Memory-Allowed", "true"
        ).lower()
        == "true",
        session_id=request.headers.get("X-Meguri-Session-ID"),
    )


def require_admin(principal: ApiPrincipal) -> None:
    if principal.actor_type is not ActorType.ADMIN:
        raise api_error(403, "admin_required", "memory administration permission is required")


async def get_authoritative_provider(request: Request) -> AuthoritativeMemoryProvider:
    orchestrator = getattr(request.app.state, "orchestrator", None)
    provider = getattr(orchestrator, "memory", None)
    if not isinstance(provider, AuthoritativeMemoryProvider):
        raise api_error(
            503,
            "memory_provider_not_authoritative",
            "authoritative memory provider is not enabled",
        )
    return provider


def get_request_id(
    request_id: Annotated[str | None, Header(alias="X-Request-ID")] = None,
) -> str:
    if request_id is None or not request_id.strip():
        raise api_error(400, "request_id_required", "X-Request-ID is required")
    if len(request_id) > 200:
        raise api_error(400, "request_id_invalid", "X-Request-ID is too long")
    return request_id.strip()


PrincipalDependency = Annotated[ApiPrincipal, Depends(get_api_principal)]
ProviderDependency = Annotated[AuthoritativeMemoryProvider, Depends(get_authoritative_provider)]
RequestIdDependency = Annotated[str, Depends(get_request_id)]
