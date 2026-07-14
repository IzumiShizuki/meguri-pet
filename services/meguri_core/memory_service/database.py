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


class MemoryDatabaseSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    environment: str = Field(pattern=r"^(dev|staging|production)$")
    tenant_id: str = Field(min_length=1, max_length=100)
    database_url: SecretStr
    mutation_allowed: bool = False
    pool_size: int = Field(default=5, ge=1, le=50)
    max_overflow: int = Field(default=5, ge=0, le=100)

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
        database_url = os.getenv("MEGURI_DATABASE_URL")
        if not database_url:
            raise RuntimeError("MEGURI_DATABASE_URL is required for native_pgvector")
        environment = os.getenv("MEGURI_ENV", "dev")
        tenant_id = os.getenv("MEGURI_TENANT_ID", f"meguri-{environment}")
        return cls(
            environment=environment,
            tenant_id=tenant_id,
            database_url=database_url,
            mutation_allowed=os.getenv("MEGURI_MUTATION_ALLOWED", "false").lower() == "true",
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
