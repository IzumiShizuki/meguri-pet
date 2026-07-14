from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.meguri_core.memory_service.database import (
    MemoryDatabaseSettings,
    create_memory_engine,
)
from services.meguri_core.memory_service.recovery import validate_memory_recovery


async def run() -> int:
    settings = MemoryDatabaseSettings.from_env()
    engine = create_memory_engine(settings)
    try:
        async with engine.connect() as connection:
            report = await validate_memory_recovery(
                connection,
                expected_database_revision=settings.expected_database_revision,
            )
    finally:
        await engine.dispose()
    print(json.dumps(report.model_dump(mode="json"), ensure_ascii=False, indent=2))
    return 0 if report.passed else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run()))
