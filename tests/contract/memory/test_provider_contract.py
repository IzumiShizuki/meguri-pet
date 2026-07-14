import os
from uuid import uuid4

import pytest
import pytest_asyncio

from services.meguri_core.memory import (
    FakeMemoryProvider,
    MemorySearchInput,
    MemoryUpsertInput,
)
from services.meguri_core.memory_service.database import MemoryDatabaseSettings
from services.meguri_core.memory_service.native_pgvector import (
    NativePgvectorMemoryProvider,
)


def memory_input(user_id, **overrides):
    values = {
        "user_id": user_id,
        "memory_type": "preference",
        "canonical_text": "User prefers unsweetened tea",
        "source_client": "website",
        "source_session": "session-web",
        "confidence": 0.95,
        "importance": 4,
    }
    values.update(overrides)
    return MemoryUpsertInput(**values)


@pytest_asyncio.fixture(params=["fake", "native_pgvector"])
async def provider(request):
    if request.param == "fake":
        yield FakeMemoryProvider()
        return
    database_url = os.getenv("MEGURI_TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("MEGURI_TEST_DATABASE_URL is required for native provider contract tests")
    native = NativePgvectorMemoryProvider(
        settings=MemoryDatabaseSettings(
            environment="dev",
            tenant_id="meguri-contract-test",
            database_url=database_url,
            mutation_allowed=True,
        )
    )
    try:
        yield native
    finally:
        await native.close()


@pytest.mark.asyncio
async def test_provider_contract_version_visibility_and_delete(provider):
    user_id = f"contract-{uuid4()}"
    first = await provider.upsert(memory_input(user_id))
    hits = await provider.search(MemorySearchInput(user_id=user_id, query="unsweetened tea"))
    assert hits and hits[0].record.canonical_text == first.canonical_text

    current = await provider.supersede(
        first.memory_id,
        memory_input(
            user_id,
            canonical_text="User now prefers black coffee",
            source_client="astrbot",
        ),
    )
    assert current.version == 2
    visible = await provider.list_records(user_id)
    assert any(record.canonical_text == "User now prefers black coffee" for record in visible)
    assert not any(
        record.status == "active" and record.canonical_text == "User prefers unsweetened tea"
        for record in visible
    )

    await provider.delete(current.memory_id)
    assert await provider.search(MemorySearchInput(user_id=user_id, query="black coffee")) == []
    exported = await provider.list_records(user_id, include_deleted=True)
    assert any(record.status == "deleted" for record in exported)
