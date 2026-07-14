import os

import pytest
from sqlalchemy.dialects import postgresql

from services.meguri_core.memory_service.database import MemoryDatabaseSettings
from services.meguri_core.memory_service.models import MemorySearchQuery
from services.meguri_core.memory_service.repository import (
    MemoryUnitOfWork,
    SqlAlchemyMemoryRepository,
)


class EmptyResult:
    def all(self):
        return []


class CapturingSession:
    def __init__(self):
        self.statement = None

    async def scalars(self, statement):
        self.statement = statement
        return EmptyResult()

    async def execute(self, statement):
        self.statement = statement
        return EmptyResult()

    async def flush(self):
        return None


def compiled(statement) -> str:
    return str(statement.compile(dialect=postgresql.dialect()))


def test_database_settings_require_postgresql_and_production_approval(monkeypatch):
    with pytest.raises(ValueError):
        MemoryDatabaseSettings(
            environment="dev",
            tenant_id="meguri-dev",
            database_url="sqlite+aiosqlite:///memory.db",
        )
    monkeypatch.delenv("MEGURI_PRODUCTION_WRITE_APPROVED", raising=False)
    with pytest.raises(ValueError):
        MemoryDatabaseSettings(
            environment="production",
            tenant_id="meguri-production",
            database_url="postgresql+asyncpg://localhost/meguri",
            mutation_allowed=True,
        )


def test_database_settings_load_only_file_secret_and_pin_release(monkeypatch, tmp_path):
    secret = tmp_path / "database-url.txt"
    secret.write_text("postgresql+asyncpg://app:password@postgres/meguri\n", encoding="utf-8")
    values = {
        "MEGURI_ENV": "staging",
        "MEGURI_TENANT_ID": "meguri-staging",
        "MEGURI_DATABASE_URL_FILE": str(secret),
        "MEGURI_DATABASE_REVISION": "20260714_0004",
        "MEGURI_EMBEDDING_MODEL_REVISION": "bge-m3-test-revision",
        "MEGURI_MUTATION_ALLOWED": "false",
    }
    with monkeypatch.context() as context:
        for key in tuple(os.environ):
            if key.startswith("MEGURI_"):
                context.delenv(key, raising=False)
        for key, value in values.items():
            context.setenv(key, value)
        settings = MemoryDatabaseSettings.from_env()

    assert settings.database_url.get_secret_value().endswith("@postgres/meguri")
    assert settings.expected_database_revision == "20260714_0004"
    assert settings.expected_embedding_model_revision == "bge-m3-test-revision"

    with monkeypatch.context() as context:
        context.setenv("MEGURI_DATABASE_URL", "postgresql+asyncpg://inline/forbidden")
        context.setenv("MEGURI_DATABASE_URL_FILE", str(secret))
        with pytest.raises(RuntimeError, match="must not be supplied inline"):
            MemoryDatabaseSettings.from_env()


@pytest.mark.asyncio
async def test_outbox_claim_uses_skip_locked():
    session = CapturingSession()
    repository = SqlAlchemyMemoryRepository(session)  # type: ignore[arg-type]
    assert await repository.claim_outbox(
        worker_id="worker-1", limit=10, lease_seconds=60
    ) == []
    sql = compiled(session.statement).upper()
    assert "FOR UPDATE SKIP LOCKED" in sql
    assert "MEMORY_OUTBOX.STATUS" in sql


@pytest.mark.asyncio
async def test_exact_vector_and_keyword_queries_apply_authority_filters():
    session = CapturingSession()
    repository = SqlAlchemyMemoryRepository(session)  # type: ignore[arg-type]
    query = MemorySearchQuery(
        tenant_id="meguri-dev",
        user_id="user-a",
        query="tea",
        query_embedding=[0.0] * 1024,
        embedding_model="BAAI/bge-m3",
        embedding_revision="0123456789abcdef",
    )
    await repository.vector_search(query)
    vector_sql = compiled(session.statement).upper()
    assert "<=>" in vector_sql
    assert "MEMORY_ITEMS.TENANT_ID" in vector_sql
    assert "MEMORY_ITEMS.USER_ID" in vector_sql
    assert "MEMORY_ITEMS.STATUS" in vector_sql
    assert "MEMORY_ITEMS.CURRENT_VERSION_ID" in vector_sql

    await repository.keyword_search(query)
    keyword_sql = compiled(session.statement).upper()
    assert "TO_TSVECTOR" in keyword_sql
    assert "MEMORY_ITEMS.TENANT_ID" in keyword_sql
    assert "MEMORY_ITEMS.USER_ID" in keyword_sql
    assert "MEMORY_ITEMS.STATUS" in keyword_sql


class RecordingTransaction:
    def __init__(self):
        self.exit_args = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        self.exit_args = args


class RecordingSession:
    def __init__(self):
        self.transaction = RecordingTransaction()
        self.closed = False

    def begin(self):
        return self.transaction

    async def close(self):
        self.closed = True


@pytest.mark.asyncio
async def test_unit_of_work_delegates_exception_to_transaction_rollback():
    session = RecordingSession()
    unit_of_work = MemoryUnitOfWork(lambda: session)  # type: ignore[arg-type]
    with pytest.raises(RuntimeError):
        async with unit_of_work:
            raise RuntimeError("force rollback")
    assert session.transaction.exit_args[0] is RuntimeError
    assert session.closed
