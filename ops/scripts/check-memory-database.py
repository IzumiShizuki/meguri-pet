"""Inspect authoritative database readiness without logging connection secrets."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from services.meguri_core.memory_service.native_pgvector import NativePgvectorMemoryProvider


async def main() -> None:
    provider = NativePgvectorMemoryProvider.from_env()
    try:
        print(json.dumps(await provider.health()))
    finally:
        await provider.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as error:
        print(f"Authoritative memory database readiness failed ({type(error).__name__}).", file=sys.stderr)
        raise SystemExit(1)
