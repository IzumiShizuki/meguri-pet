from __future__ import annotations

import hashlib
import hmac
from typing import Protocol

from pydantic import SecretStr

from .models import StrictModel


class IdentityLookup(Protocol):
    async def resolve_identity(
        self,
        *,
        tenant_id: str,
        platform: str,
        platform_user_id: str,
    ) -> str | None: ...


class ResolvedIdentity(StrictModel):
    tenant_id: str
    user_id: str
    client_id: str
    session_id: str
    platform: str
    verified_binding: bool
    formal_memory_allowed: bool


class IdentityResolver:
    def __init__(self, lookup: IdentityLookup, *, isolation_salt: SecretStr | str) -> None:
        raw_salt = (
            isolation_salt.get_secret_value()
            if isinstance(isolation_salt, SecretStr)
            else isolation_salt
        )
        if len(raw_salt) < 16:
            raise ValueError("identity isolation salt must contain at least 16 characters")
        self.lookup = lookup
        self._salt = raw_salt.encode("utf-8")

    async def resolve(
        self,
        *,
        tenant_id: str,
        platform: str,
        platform_user_id: str,
        client_id: str,
        session_id: str,
    ) -> ResolvedIdentity:
        if not all(
            value.strip()
            for value in (tenant_id, platform, platform_user_id, client_id, session_id)
        ):
            raise ValueError("identity fields must not be empty")
        user_id = await self.lookup.resolve_identity(
            tenant_id=tenant_id,
            platform=platform,
            platform_user_id=platform_user_id,
        )
        if user_id is not None:
            return ResolvedIdentity(
                tenant_id=tenant_id,
                user_id=user_id,
                client_id=client_id,
                session_id=session_id,
                platform=platform,
                verified_binding=True,
                formal_memory_allowed=True,
            )
        opaque = hmac.new(
            self._salt,
            "\x1f".join((tenant_id, platform, platform_user_id)).encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return ResolvedIdentity(
            tenant_id=tenant_id,
            user_id=f"unbound-{opaque[:32]}",
            client_id=client_id,
            session_id=session_id,
            platform=platform,
            verified_binding=False,
            formal_memory_allowed=False,
        )
