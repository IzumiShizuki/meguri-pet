from __future__ import annotations

import os

from .memory import FakeMemoryProvider, MemoryProvider


def create_memory_provider_from_env() -> MemoryProvider:
    provider = os.getenv("MEGURI_MEMORY_PROVIDER", "fake").strip().casefold()
    if provider == "fake":
        return FakeMemoryProvider()
    if provider == "native_pgvector":
        from .memory_service.native_pgvector import NativePgvectorMemoryProvider

        return NativePgvectorMemoryProvider.from_env()
    raise RuntimeError(
        "MEGURI_MEMORY_PROVIDER must be fake or native_pgvector; MemoryOS is never authoritative"
    )
