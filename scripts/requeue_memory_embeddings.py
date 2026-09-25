"""Queue active memory versions that need a vector for the active provider.

This is intentionally an explicit operator action rather than an Alembic side
effect: migrations must remain database-only, while embedding is an external
provider call performed later by the normal transactional-Outbox worker.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path
from uuid import uuid4

from sqlalchemy import and_, select


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.meguri_core.memory_service.database import (  # noqa: E402
    MemoryDatabaseSettings,
    create_memory_engine,
    create_session_factory,
)
from services.meguri_core.memory_service.embedding import (  # noqa: E402
    create_runtime_embedding_provider,
)
from services.meguri_core.memory_service.enums import OutboxStatus  # noqa: E402
from services.meguri_core.memory_service.models import MemoryStatus  # noqa: E402
from services.meguri_core.memory_service.orm import (  # noqa: E402
    MemoryEmbeddingRow,
    MemoryItemRow,
    MemoryOutboxRow,
)
from services.meguri_core.memory_service.repository import utc_now  # noqa: E402


ACTIVE_OUTBOX_STATUSES = (
    OutboxStatus.PENDING.value,
    OutboxStatus.FAILED.value,
    OutboxStatus.PROCESSING.value,
)


async def requeue_active_memory_embeddings(*, dry_run: bool = False) -> dict[str, int]:
    """Enqueue one idempotent embedding request for every unembedded active item."""

    settings = MemoryDatabaseSettings.from_env()
    provider = create_runtime_embedding_provider(
        expected_revision=settings.expected_embedding_model_revision
    )
    if provider is None:
        raise RuntimeError("embedding requeue requires an enabled embedding provider")

    engine = create_memory_engine(settings)
    try:
        session_factory = create_session_factory(engine)
        async with session_factory() as session:
            missing = await session.execute(
                select(MemoryItemRow.current_version_id, MemoryItemRow.tenant_id)
                .outerjoin(
                    MemoryEmbeddingRow,
                    and_(
                        MemoryEmbeddingRow.version_id == MemoryItemRow.current_version_id,
                        MemoryEmbeddingRow.embedding_model == provider.model,
                        MemoryEmbeddingRow.embedding_revision == provider.revision,
                        MemoryEmbeddingRow.status == "ready",
                    ),
                )
                .where(
                    MemoryItemRow.status == MemoryStatus.ACTIVE.value,
                    MemoryItemRow.current_version_id.is_not(None),
                    MemoryEmbeddingRow.embedding_id.is_(None),
                )
                .order_by(MemoryItemRow.created_at, MemoryItemRow.memory_id)
            )
            candidates = [(row[0], row[1]) for row in missing.all()]
            queued = skipped = 0
            for version_id, tenant_id in candidates:
                already_queued = await session.scalar(
                    select(MemoryOutboxRow.outbox_id).where(
                        MemoryOutboxRow.event_type == "embedding.requested",
                        MemoryOutboxRow.aggregate_id == version_id,
                        MemoryOutboxRow.status.in_(ACTIVE_OUTBOX_STATUSES),
                    )
                )
                if already_queued:
                    skipped += 1
                    continue
                queued += 1
                if not dry_run:
                    session.add(
                        MemoryOutboxRow(
                            outbox_id=uuid4(),
                            event_type="embedding.requested",
                            aggregate_id=version_id,
                            payload={"tenant_id": tenant_id, "version_id": str(version_id)},
                            status=OutboxStatus.PENDING.value,
                            attempts=0,
                            available_at=utc_now(),
                        )
                    )
            if not dry_run:
                await session.commit()
            return {"candidates": len(candidates), "queued": queued, "skipped": skipped}
    finally:
        await engine.dispose()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Queue active memory vectors for the configured embedding provider"
    )
    parser.add_argument("--dry-run", action="store_true")
    arguments = parser.parse_args()
    print(json.dumps(asyncio.run(requeue_active_memory_embeddings(dry_run=arguments.dry_run)), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
