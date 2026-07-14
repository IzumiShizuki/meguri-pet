from __future__ import annotations

import os

import pytest

from services.meguri_core.memory_service.database import (
    MemoryDatabaseSettings,
    create_memory_engine,
)
from services.meguri_core.memory_service.recovery import validate_memory_recovery
from services.meguri_core.memory_service.release import DATABASE_REVISION


@pytest.mark.asyncio
async def test_live_recovered_database_integrity() -> None:
    database_url = os.getenv("MEGURI_TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("MEGURI_TEST_DATABASE_URL is required for recovery validation")
    engine = create_memory_engine(
        MemoryDatabaseSettings(
            environment="dev",
            tenant_id="meguri-recovery-test",
            database_url=database_url,
        )
    )
    try:
        async with engine.connect() as connection:
            report = await validate_memory_recovery(
                connection,
                expected_database_revision=DATABASE_REVISION,
            )
    finally:
        await engine.dispose()
    assert report.passed, report.model_dump(mode="json")
