from __future__ import annotations

import os
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)


class MemoryDatabaseSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    environment: str = Field(pattern=r"^(dev|staging|production)$")
    tenant_id: str = Field(min_length=1, max_length=100)
    database_url: SecretStr
    mutation_allowed: bool = False
    pool_size: int = Field(default=5, ge=1, le=50)
    max_overflow: int = Field(default=5, ge=0, le=100)
    expected_database_revision: str | None = None
    expected_embedding_model_revision: str | None = None

    @model_validator(mode="after")
    def validate_database_contract(self) -> "MemoryDatabaseSettings":
        url = make_url(self.database_url.get_secret_value())
        if url.drivername != "postgresql+asyncpg":
            raise ValueError("memory database must use postgresql+asyncpg")
        if self.environment == "production" and self.mutation_allowed:
            if os.getenv("MEGURI_PRODUCTION_WRITE_APPROVED") != "true":
                raise ValueError("production memory writes require explicit release approval")
        return self

    @classmethod
    def from_env(cls) -> "MemoryDatabaseSettings":
        environment = os.getenv("MEGURI_ENV", "dev")
        database_url_file = os.getenv("MEGURI_DATABASE_URL_FILE")
        database_url = (
            load_secret_text(database_url_file, variable="MEGURI_DATABASE_URL_FILE")
            if database_url_file
            else os.getenv("MEGURI_DATABASE_URL")
        )
        if environment in {"staging", "production"} and not database_url_file:
            raise RuntimeError(
                "staging and production require MEGURI_DATABASE_URL_FILE"
            )
        if not database_url:
            raise RuntimeError(
                "MEGURI_DATABASE_URL_FILE or dev-only MEGURI_DATABASE_URL is required"
            )
        tenant_id = os.getenv("MEGURI_TENANT_ID", f"meguri-{environment}")
        return cls(
            environment=environment,
            tenant_id=tenant_id,
            database_url=database_url,
            mutation_allowed=os.getenv("MEGURI_MUTATION_ALLOWED", "false").lower() == "true",
            expected_database_revision=os.getenv("MEGURI_DATABASE_REVISION"),
            expected_embedding_model_revision=os.getenv(
                "MEGURI_EMBEDDING_MODEL_REVISION"
            ),
        )


def load_secret_text(path_value: str, *, variable: str) -> str:
    path = Path(path_value)
    if not path.is_absolute():
        raise RuntimeError(f"{variable} must be an absolute path")
    try:
        if not path.is_file():
            raise RuntimeError(f"{variable} does not reference a file")
        if path.stat().st_size > 8192:
            raise RuntimeError(f"{variable} is unexpectedly large")
        value = path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise RuntimeError(f"unable to read {variable}") from exc
    if not value:
        raise RuntimeError(f"{variable} is empty")
    return value


def create_memory_engine(settings: MemoryDatabaseSettings) -> AsyncEngine:
    return create_async_engine(
        settings.database_url.get_secret_value(),
        pool_pre_ping=True,
        pool_size=settings.pool_size,
        max_overflow=settings.max_overflow,
        connect_args={"server_settings": {"application_name": f"meguri-memory-{settings.environment}"}},
    )


def create_session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
