from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter
from pydantic import Field

from .api_auth import (
    PrincipalDependency,
    ProviderDependency,
    RequestIdDependency,
    require_admin,
)
from .memory_api import memory_call
from .memory_service.models import IdentityBindingCreate, StrictModel


router = APIRouter(prefix="/v1/identity-bindings", tags=["memory-identity"])


class BindingCreateRequest(StrictModel):
    user_id: str = Field(min_length=1, max_length=200)
    platform: str = Field(min_length=1, max_length=100)
    platform_user_id: str = Field(min_length=1, max_length=300)
    verification_method: str = Field(min_length=1, max_length=100)


@router.get("")
async def list_bindings(
    principal: PrincipalDependency,
    provider: ProviderDependency,
):
    return {
        "items": await memory_call(
            provider.list_identity_bindings(
                tenant_id=principal.tenant_id,
                user_id=principal.user_id,
            )
        )
    }


@router.post("", status_code=201)
async def create_binding(
    body: BindingCreateRequest,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    require_admin(principal)
    return await memory_call(
        provider.bind_identity(
            IdentityBindingCreate(
                tenant_id=principal.tenant_id,
                user_id=body.user_id,
                platform=body.platform,
                platform_user_id=body.platform_user_id,
                verification_method=body.verification_method,
            ),
            actor=principal.memory_actor(),
            request_id=request_id,
        )
    )


@router.delete("/{binding_id}", status_code=204)
async def delete_binding(
    binding_id: UUID,
    principal: PrincipalDependency,
    provider: ProviderDependency,
    request_id: RequestIdDependency,
):
    require_admin(principal)
    await memory_call(
        provider.unbind_identity(
            binding_id,
            actor=principal.memory_actor(),
            request_id=request_id,
        )
    )
