from __future__ import annotations

import hashlib
import hmac

from .models import IdentityContext, PlatformMessage


class IdentityBindingStore:
    """Maps platform identities without exposing raw identifiers to meguri-core."""

    def __init__(self, salt: str = "meguri-local-development") -> None:
        if not salt:
            raise ValueError("identity salt must not be empty")
        self._salt = salt.encode("utf-8")
        self._bindings: dict[tuple[str, str], str] = {}

    def bind(self, platform: str, platform_user_id: str, meguri_user_id: str) -> None:
        if not all((platform, platform_user_id, meguri_user_id)):
            raise ValueError("binding values must not be empty")
        self._bindings[(platform, platform_user_id)] = meguri_user_id

    def resolve(self, message: PlatformMessage) -> IdentityContext:
        binding_key = (message.platform, message.sender_id)
        user_id = self._bindings.get(binding_key) or self._opaque_id(
            "user", message.platform, message.sender_id
        )
        session_id = self._opaque_id(
            "session",
            message.platform,
            message.account_id,
            message.chat_type,
            message.conversation_id,
            message.sender_id,
        )
        return IdentityContext(meguri_user_id=user_id, session_id=session_id)

    def _opaque_id(self, prefix: str, *parts: str) -> str:
        value = "\x1f".join(parts).encode("utf-8")
        digest = hmac.new(self._salt, value, hashlib.sha256).hexdigest()[:24]
        return f"{prefix}_{digest}"
