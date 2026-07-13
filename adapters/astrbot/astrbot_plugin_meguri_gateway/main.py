"""Offline-safe plugin facade.

The production AstrBot registration layer is intentionally kept thin. It should
convert an AstrBot message event to PlatformMessage, call this facade, and send
the returned text. No provider, database, TTS, or memory logic belongs here.
"""

import os

from .client import HttpMeguriCoreClient
from .gateway import MeguriGateway
from .identity import IdentityBindingStore


def create_gateway(
    core_url: str = "http://127.0.0.1:8100",
    timeout_seconds: float = 8.0,
    identity_salt: str | None = None,
) -> MeguriGateway:
    resolved_salt = identity_salt or os.getenv("MEGURI_IDENTITY_SALT")
    if not resolved_salt:
        raise RuntimeError("MEGURI_IDENTITY_SALT is required")
    return MeguriGateway(
        core=HttpMeguriCoreClient(base_url=core_url, timeout_seconds=timeout_seconds),
        identities=IdentityBindingStore(salt=resolved_salt),
    )
