from __future__ import annotations

import os

from .memory import FakeMemoryProvider, MemoryProvider


def create_memory_provider_from_env() -> MemoryProvider:
    environment = os.getenv("MEGURI_ENV", "dev")
    if os.getenv("MEGURI_DATABASE_URL", "").strip():
        raise RuntimeError(
            "MEGURI_DATABASE_URL must not be supplied inline; use MEGURI_DATABASE_URL_FILE"
        )
    configured = os.getenv("MEGURI_MEMORY_PROVIDER")
    if configured is None:
        if environment != "dev":
            raise RuntimeError(
                "staging and production require MEGURI_MEMORY_PROVIDER=native_pgvector"
            )
        has_database_configuration = bool(os.getenv("MEGURI_DATABASE_URL_FILE"))
        provider = "native_pgvector" if has_database_configuration else "fake"
    else:
        provider = configured.strip().casefold()
    if provider == "fake":
        if environment != "dev":
            raise RuntimeError("fake memory is restricted to development")
        return FakeMemoryProvider()
    if provider == "native_pgvector":
        from .memory_service.native_pgvector import NativePgvectorMemoryProvider

        return NativePgvectorMemoryProvider.from_env()
    raise RuntimeError(
        "MEGURI_MEMORY_PROVIDER must be fake or native_pgvector; MemoryOS is never authoritative"
    )
