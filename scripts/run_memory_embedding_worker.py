from __future__ import annotations

import argparse
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
    create_session_factory,
)
from services.meguri_core.memory_service.embedding import (
    EmbeddingWorker,
    create_runtime_embedding_provider,
)
from services.meguri_core.memory_service.repository import MemoryUnitOfWorkFactory


async def run_once(worker_id: str, batch_size: int) -> dict[str, int]:
    settings = MemoryDatabaseSettings.from_env()
    provider = create_runtime_embedding_provider(
        expected_revision=settings.expected_embedding_model_revision
    )
    if provider is None:
        raise RuntimeError("embedding worker cannot run with a disabled backend")
    engine = create_memory_engine(settings)
    try:
        worker = EmbeddingWorker(
            MemoryUnitOfWorkFactory(create_session_factory(engine)),
            provider,
            worker_id=worker_id,
            batch_size=batch_size,
        )
        return await worker.run_once()
    finally:
        await engine.dispose()


def main() -> int:
    parser = argparse.ArgumentParser(description="Run one memory embedding outbox batch")
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--batch-size", type=int, default=20)
    arguments = parser.parse_args()
    result = asyncio.run(run_once(arguments.worker_id, arguments.batch_size))
    print(json.dumps(result, sort_keys=True))
    return 0 if result["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
