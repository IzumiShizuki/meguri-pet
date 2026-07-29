import os
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy.dialects import postgresql

from services.meguri_core.memory_service.contracts import MemoryConflictError
from services.meguri_core.memory_service.database import MemoryDatabaseSettings
from services.meguri_core.memory_service.enums import CandidateStatus, OutboxStatus
from services.meguri_core.memory_service.models import (
    MemoryCandidateCreate,
    MemorySearchQuery,
)
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
        self.parameters = None

    async def scalars(self, statement):
        self.statement = statement
        return EmptyResult()

    async def execute(self, statement, *args, **_kwargs):
        self.statement = statement
        self.parameters = args[0] if args else None
        return EmptyResult()

    async def flush(self):
        return None


class ScalarSession:
    def __init__(self, row):
        self.row = row

    async def scalar(self, _statement):
        return self.row


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
async def test_outbox_ack_requires_current_worker_and_claim_token():
    old_claim = datetime.now(timezone.utc) - timedelta(minutes=10)
    new_claim = datetime.now(timezone.utc)
    row = SimpleNamespace(
        status=OutboxStatus.PROCESSING.value,
        locked_by="worker-1",
        locked_at=new_claim,
        completed_at=None,
        last_error=None,
    )
    repository = SqlAlchemyMemoryRepository(ScalarSession(row))  # type: ignore[arg-type]

    assert not await repository.complete_outbox(
        uuid4(), worker_id="worker-1", claim_locked_at=old_claim
    )
    assert row.status == OutboxStatus.PROCESSING.value
    assert row.locked_at == new_claim

    assert not await repository.complete_outbox(
        uuid4(), worker_id="worker-2", claim_locked_at=new_claim
    )
    assert await repository.complete_outbox(
        uuid4(), worker_id="worker-1", claim_locked_at=new_claim
    )
    assert row.status == OutboxStatus.COMPLETED.value
    assert row.locked_by is None


@pytest.mark.asyncio
async def test_outbox_retry_rejects_stale_claim_owner():
    old_claim = datetime.now(timezone.utc) - timedelta(minutes=10)
    new_claim = datetime.now(timezone.utc)
    row = SimpleNamespace(
        status=OutboxStatus.PROCESSING.value,
        locked_by="new-worker",
        locked_at=new_claim,
        attempts=2,
        available_at=new_claim,
        last_error=None,
    )
    repository = SqlAlchemyMemoryRepository(ScalarSession(row))  # type: ignore[arg-type]

    assert not await repository.fail_outbox(
        uuid4(),
        worker_id="old-worker",
        claim_locked_at=old_claim,
        error_code="TimeoutError",
        max_attempts=5,
        retry_delay_seconds=30,
    )
    assert row.status == OutboxStatus.PROCESSING.value
    assert row.attempts == 2
    assert row.locked_by == "new-worker"


@pytest.mark.asyncio
async def test_idempotency_uses_transaction_advisory_lock():
    session = CapturingSession()
    repository = SqlAlchemyMemoryRepository(session)  # type: ignore[arg-type]
    await repository.lock_idempotency_key(
        "tenant-a", "candidate.create.scope", "request-1"
    )
    assert "PG_ADVISORY_XACT_LOCK" in str(session.statement).upper()
    assert "HASHTEXTEXTENDED" in str(session.statement).upper()
    assert session.parameters == {
        "key": '["tenant-a","candidate.create.scope","request-1"]'
    }
    assert "\0" not in session.parameters["key"]


@pytest.mark.asyncio
async def test_repository_refuses_unredacted_rejected_candidate():
    repository = SqlAlchemyMemoryRepository(CapturingSession())  # type: ignore[arg-type]
    unsafe = MemoryCandidateCreate(
        tenant_id="meguri-dev",
        user_id="user-a",
        memory_type="user_profile",
        content_text="My API key is sk-repository-bypass",
        content_json={"raw": "sk-repository-bypass"},
        confidence=1.0,
        source_client_id="website",
        source_session_id="session-web",
        source_turn_id="turn-unsafe",
    )

    with pytest.raises(ValueError, match="must be redacted"):
        await repository.create_candidate(
            unsafe,
            status=CandidateStatus.PENDING_REVIEW,
        )


@pytest.mark.asyncio
async def test_repository_cannot_create_an_already_approved_candidate():
    repository = SqlAlchemyMemoryRepository(CapturingSession())  # type: ignore[arg-type]
    safe = MemoryCandidateCreate(
        tenant_id="meguri-dev",
        user_id="user-a",
        memory_type="user_preference",
        content_text="User likes tea",
        confidence=0.9,
        source_client_id="website",
        source_session_id="session-web",
        source_turn_id="turn-safe",
    )
    with pytest.raises(MemoryConflictError, match="cannot be created directly"):
        await repository.create_candidate(safe, status=CandidateStatus.APPROVED)


@pytest.mark.asyncio
async def test_exact_vector_and_keyword_queries_apply_authority_filters():
    session = CapturingSession()
    repository = SqlAlchemyMemoryRepository(session)  # type: ignore[arg-type]
    query = MemorySearchQuery(
        tenant_id="meguri-dev",
        user_id="user-a",
        query="tea",
        query_embedding=[0.0] * 2048,
        embedding_model="text-embedding-v4",
        embedding_revision="0123456789abcdef",
    )
    await repository.vector_search(query)
    vector_sql = compiled(session.statement).upper()
    assert "<=>" in vector_sql
    assert "MEMORY_ITEMS.TENANT_ID" in vector_sql
    assert "MEMORY_ITEMS.USER_ID" in vector_sql
    assert "MEMORY_ITEMS.STATUS" in vector_sql
    assert "MEMORY_ITEMS.CURRENT_VERSION_ID" in vector_sql
    assert "MEMORY_ITEMS.LAST_STABLE_VERSION_ID" in vector_sql
    assert "CASE WHEN" in vector_sql
    assert "MEMORY_VERSIONS.STATUS" in vector_sql
    assert "MEMORY_ITEMS.EFFECTIVE_AT" in vector_sql
    assert "MEMORY_CANDIDATES.ACCEPTED_MEMORY_ID" in vector_sql

    await repository.keyword_search(query)
    keyword_sql = compiled(session.statement).upper()
    assert "TO_TSVECTOR" in keyword_sql
    assert "MEMORY_ITEMS.TENANT_ID" in keyword_sql
    assert "MEMORY_ITEMS.USER_ID" in keyword_sql
    assert "MEMORY_ITEMS.STATUS" in keyword_sql
    assert "MEMORY_ITEMS.LAST_STABLE_VERSION_ID" in keyword_sql
    assert "CASE WHEN" in keyword_sql
    assert "MEMORY_VERSIONS.STATUS" in keyword_sql
    assert "MEMORY_CANDIDATES.ACCEPTED_MEMORY_ID" in keyword_sql

    structured = query.model_copy(update={"canonical_key": "preference:drink"})
    await repository.structured_search(structured)
    structured_sql = compiled(session.statement).upper()
    assert "MEMORY_ITEMS.CANONICAL_KEY" in structured_sql
    assert "MEMORY_ITEMS.TENANT_ID" in structured_sql
    assert "MEMORY_ITEMS.USER_ID" in structured_sql
    assert "MEMORY_ITEMS.CURRENT_VERSION_ID" in structured_sql
    assert "MEMORY_ITEMS.LAST_STABLE_VERSION_ID" in structured_sql
    assert "CASE WHEN" in structured_sql


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
