from __future__ import annotations

from pathlib import Path

import pytest

from services.meguri_core.memory_service.database import MemoryDatabaseSettings


def clear_database_environment(monkeypatch) -> None:
    for name in (
        "MEGURI_DATABASE_URL",
        "MEGURI_DATABASE_URL_FILE",
        "MEGURI_DATABASE_REVISION",
        "MEGURI_EMBEDDING_MODEL_REVISION",
        "MEGURI_MUTATION_ALLOWED",
        "MEGURI_PRODUCTION_WRITE_APPROVED",
    ):
        monkeypatch.delenv(name, raising=False)


def test_dev_may_use_direct_url_for_local_contract_tests(monkeypatch) -> None:
    clear_database_environment(monkeypatch)
    monkeypatch.setenv("MEGURI_ENV", "dev")
    monkeypatch.setenv(
        "MEGURI_DATABASE_URL",
        "postgresql+asyncpg://memory_app@localhost/meguri_dev",
    )

    settings = MemoryDatabaseSettings.from_env()

    assert settings.environment == "dev"
    assert settings.database_url.get_secret_value().endswith("/meguri_dev")


def test_staging_requires_absolute_secret_file(monkeypatch, tmp_path: Path) -> None:
    clear_database_environment(monkeypatch)
    monkeypatch.setenv("MEGURI_ENV", "staging")
    monkeypatch.setenv(
        "MEGURI_DATABASE_URL",
        "postgresql+asyncpg://must-not-be-used@localhost/meguri_staging",
    )
    with pytest.raises(RuntimeError, match="MEGURI_DATABASE_URL_FILE"):
        MemoryDatabaseSettings.from_env()

    secret = tmp_path / "database-url.txt"
    secret.write_text(
        "postgresql+asyncpg://memory_app@postgres/meguri_staging\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("MEGURI_DATABASE_URL_FILE", str(secret.resolve()))
    monkeypatch.setenv("MEGURI_DATABASE_REVISION", "20260714_0004")
    monkeypatch.setenv(
        "MEGURI_EMBEDDING_MODEL_REVISION",
        "5617a9f61b028005a4858fdac845db406aefb181",
    )

    settings = MemoryDatabaseSettings.from_env()

    assert settings.database_url.get_secret_value().endswith("/meguri_staging")
    assert settings.expected_database_revision == "20260714_0004"
    assert settings.expected_embedding_model_revision.startswith("5617a9f")
