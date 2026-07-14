from __future__ import annotations

import pytest

from services.meguri_core.memory import FakeMemoryProvider
from services.meguri_core.memory_provider_factory import create_memory_provider_from_env
from services.meguri_core.memory_service.native_pgvector import (
    NativePgvectorMemoryProvider,
)


@pytest.mark.asyncio
async def test_unconfigured_local_runtime_stays_fake(monkeypatch) -> None:
    for name in (
        "MEGURI_MEMORY_PROVIDER",
        "MEGURI_DATABASE_URL",
        "MEGURI_DATABASE_URL_FILE",
    ):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("MEGURI_ENV", "dev")

    assert isinstance(create_memory_provider_from_env(), FakeMemoryProvider)


@pytest.mark.asyncio
async def test_configured_dev_database_file_selects_native_by_default(monkeypatch, tmp_path) -> None:
    monkeypatch.delenv("MEGURI_MEMORY_PROVIDER", raising=False)
    monkeypatch.delenv("MEGURI_DATABASE_URL", raising=False)
    monkeypatch.setenv("MEGURI_ENV", "dev")
    secret = tmp_path / "database-url.txt"
    secret.write_text(
        "postgresql+asyncpg://memory_app@localhost/meguri_dev\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("MEGURI_DATABASE_URL_FILE", str(secret))
    monkeypatch.setenv("MEGURI_DATABASE_REVISION", "20260714_0004")
    monkeypatch.setenv("MEGURI_EMBEDDING_MODEL_REVISION", "bge-m3-test-revision")

    provider = create_memory_provider_from_env()
    assert isinstance(provider, NativePgvectorMemoryProvider)
    await provider.close()


def test_inline_database_url_is_never_accepted(monkeypatch) -> None:
    monkeypatch.setenv("MEGURI_ENV", "dev")
    monkeypatch.setenv(
        "MEGURI_DATABASE_URL",
        "postgresql+asyncpg://memory_app@localhost/meguri_dev",
    )
    with pytest.raises(RuntimeError, match="must not be supplied inline"):
        create_memory_provider_from_env()


def test_memoryos_can_never_be_selected_as_authority(monkeypatch) -> None:
    monkeypatch.setenv("MEGURI_ENV", "dev")
    monkeypatch.setenv("MEGURI_MEMORY_PROVIDER", "memoryos")
    with pytest.raises(RuntimeError, match="never authoritative"):
        create_memory_provider_from_env()


@pytest.mark.parametrize("environment", ["staging", "production"])
def test_non_dev_never_silently_falls_back_to_fake(monkeypatch, environment) -> None:
    monkeypatch.setenv("MEGURI_ENV", environment)
    monkeypatch.delenv("MEGURI_MEMORY_PROVIDER", raising=False)
    with pytest.raises(RuntimeError, match="native_pgvector"):
        create_memory_provider_from_env()

    monkeypatch.setenv("MEGURI_MEMORY_PROVIDER", "fake")
    with pytest.raises(RuntimeError, match="restricted to development"):
        create_memory_provider_from_env()
