from __future__ import annotations

import os
from pathlib import Path

import pytest

from services.meguri_core.memory_service.database import (
    MemoryDatabaseSettings,
)
from services.meguri_core.memory_service.native_pgvector import (
    NativePgvectorMemoryProvider,
)
from services.meguri_core.memory_service.recovery import (
    RecoveryRecallCorpus,
    validate_fixed_recall,
    validate_memory_recovery,
)
from services.meguri_core.memory_service.release import DATABASE_REVISION


@pytest.mark.asyncio
async def test_live_recovered_database_integrity() -> None:
    database_url = os.getenv("MEGURI_TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("MEGURI_TEST_DATABASE_URL is required for recovery validation")
    settings = MemoryDatabaseSettings(
        environment="dev",
        tenant_id="meguri-recovery-test",
        database_url=database_url,
    )
    provider = NativePgvectorMemoryProvider(settings=settings)
    try:
        fixed_recall = None
        corpus_path = os.getenv("MEGURI_TEST_RECOVERY_RECALL_CORPUS")
        if corpus_path:
            corpus = RecoveryRecallCorpus.model_validate_json(
                Path(corpus_path).read_text(encoding="utf-8")
            )
            fixed_recall = await validate_fixed_recall(provider, corpus)
        assert provider.engine is not None
        async with provider.engine.connect() as connection:
            report = await validate_memory_recovery(
                connection,
                expected_database_revision=DATABASE_REVISION,
                fixed_recall=fixed_recall,
                require_fixed_recall=os.getenv(
                    "MEGURI_REQUIRE_RECOVERY_RECALL", "false"
                ).lower()
                == "true",
            )
    finally:
        await provider.close()
    assert report.passed, report.model_dump(mode="json")
