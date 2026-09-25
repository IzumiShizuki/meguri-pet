from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path
from uuid import UUID


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.meguri_core.memory_service.database import (  # noqa: E402
    MemoryDatabaseSettings,
    create_memory_engine,
    create_session_factory,
)
from services.meguri_core.memory_service.file_mirror import (  # noqa: E402
    FileMirrorProjector,
    MemoryProjectionRepairService,
)
from services.meguri_core.memory_service.repository import (  # noqa: E402
    MemoryUnitOfWorkFactory,
)


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("value must be at least 1")
    return parsed


def positive_float(value: str) -> float:
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be greater than 0")
    return parsed


def resolve_mirror_root(argument: Path | None) -> Path:
    configured = argument or (
        Path(value) if (value := os.getenv("MEGURI_MEMORY_MIRROR_ROOT", "").strip()) else None
    )
    if configured is None:
        raise RuntimeError(
            "memory mirror root is required via --mirror-root or "
            "MEGURI_MEMORY_MIRROR_ROOT"
        )
    return configured.expanduser().resolve()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the memory file mirror projector")
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--mirror-root", type=Path)
    parser.add_argument("--batch-size", type=positive_int, default=20)
    parser.add_argument("--max-attempts", type=positive_int, default=5)
    parser.add_argument("--poll-interval", type=positive_float, default=5.0)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument(
        "--continuous", action="store_true", help="poll until interrupted"
    )
    actions.add_argument(
        "--requeue-dead-letters",
        action="store_true",
        help="requeue only file mirror dead letters and exit",
    )
    actions.add_argument(
        "--repair",
        nargs="+",
        type=UUID,
        metavar="MEMORY_ID",
        help="rebuild selected files from authoritative database records",
    )
    return parser.parse_args(argv)


async def poll_forever(
    projector: FileMirrorProjector,
    poll_interval: float,
) -> None:
    while True:
        result = await projector.run_once()
        print(json.dumps(result, sort_keys=True), flush=True)
        await asyncio.sleep(poll_interval)


async def run(arguments: argparse.Namespace) -> int:
    settings = MemoryDatabaseSettings.from_env()
    mirror_root = resolve_mirror_root(arguments.mirror_root)
    engine = create_memory_engine(settings)
    try:
        uow_factory = MemoryUnitOfWorkFactory(create_session_factory(engine))
        projector = FileMirrorProjector(
            uow_factory,
            mirror_root,
            worker_id=arguments.worker_id,
            batch_size=arguments.batch_size,
            max_attempts=arguments.max_attempts,
        )
        if arguments.requeue_dead_letters:
            count = await MemoryProjectionRepairService(
                uow_factory
            ).requeue_file_mirror_dead_letters()
            print(json.dumps({"requeued": count}, sort_keys=True))
            return 0
        if arguments.repair:
            result = await projector.repair(
                tenant_id=settings.tenant_id, memory_ids=arguments.repair
            )
            print(json.dumps(result, sort_keys=True))
            return 0 if result["failed"] == 0 and result["missing"] == 0 else 1
        if arguments.continuous:
            await poll_forever(projector, arguments.poll_interval)
            return 0
        result = await projector.run_once()
        print(json.dumps(result, sort_keys=True))
        return 0 if result["failed"] == 0 else 1
    finally:
        await engine.dispose()


def main(argv: list[str] | None = None) -> int:
    arguments = parse_args(argv)
    try:
        return asyncio.run(run(arguments))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
