from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate a restored authoritative memory database"
    )
    parser.add_argument(
        "--recall-corpus",
        type=Path,
        default=(
            Path(value)
            if (value := os.getenv("MEGURI_RECOVERY_RECALL_CORPUS"))
            else None
        ),
        help="approved JSON fixed-recall corpus to execute against the restore",
    )
    parser.add_argument(
        "--require-fixed-recall",
        action="store_true",
        default=os.getenv("MEGURI_REQUIRE_RECOVERY_RECALL", "false").lower()
        == "true",
        help="fail when no recall corpus is supplied",
    )
    return parser.parse_args()


async def run(arguments: argparse.Namespace) -> int:
    settings = MemoryDatabaseSettings.from_env()
    provider = NativePgvectorMemoryProvider(settings=settings)
    try:
        fixed_recall = None
        if arguments.recall_corpus is not None:
            corpus = RecoveryRecallCorpus.model_validate_json(
                arguments.recall_corpus.read_text(encoding="utf-8")
            )
            fixed_recall = await validate_fixed_recall(provider, corpus)
        if provider.engine is None:
            raise RuntimeError("native memory provider did not create an engine")
        async with provider.engine.connect() as connection:
            report = await validate_memory_recovery(
                connection,
                expected_database_revision=settings.expected_database_revision,
                fixed_recall=fixed_recall,
                require_fixed_recall=arguments.require_fixed_recall,
            )
    finally:
        await provider.close()
    print(json.dumps(report.model_dump(mode="json"), ensure_ascii=False, indent=2))
    return 0 if report.passed else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run(parse_args())))
