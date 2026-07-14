from __future__ import annotations

import os

from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from ..secrets import read_secret


class MemoryDatabaseSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    environment: str = Field(pattern=r"^(dev|staging|production)$")
    tenant_id: str = Field(min_length=1, max_length=100)
    database_url: SecretStr
    mutation_allowed: bool = False
    expected_database_revision: str | None = Field(default=None, min_length=1, max_length=100)
    expected_embedding_model_revision: str | None = Field(default=None, min_length=1, max_length=300)
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
        database_url = read_secret(os.environ, "MEGURI_DATABASE_URL")
        environment = os.getenv("MEGURI_ENV", "dev")
        tenant_id = os.getenv("MEGURI_TENANT_ID", f"meguri-{environment}")
        expected_database_revision = os.getenv("MEGURI_DATABASE_REVISION", "").strip()
        if not expected_database_revision:
            raise RuntimeError("MEGURI_DATABASE_REVISION is required for native_pgvector")
        expected_embedding_model_revision = os.getenv(
            "MEGURI_EMBEDDING_MODEL_REVISION", ""
        ).strip()
        if not expected_embedding_model_revision:
            raise RuntimeError(
                "MEGURI_EMBEDDING_MODEL_REVISION is required for native_pgvector"
            )
        return cls(
            environment=environment,
            tenant_id=tenant_id,
            database_url=database_url,
            mutation_allowed=os.getenv("MEGURI_MUTATION_ALLOWED", "false").lower() == "true",
            expected_database_revision=expected_database_revision,
            expected_embedding_model_revision=expected_embedding_model_revision,
        )


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
