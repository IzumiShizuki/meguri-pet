"""Build the versioned canonical Lore RAG vectors through Model Studio APIs."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.meguri_core.memory_service.embedding import create_runtime_embedding_provider
from services.meguri_core.rag_api import build_dashscope_rag_vectors


async def run(data_root: Path, output: Path | None, batch_size: int) -> dict[str, object]:
    provider = create_runtime_embedding_provider()
    if provider is None:
        raise RuntimeError("DashScope RAG vector build requires an embedding provider")
    target = await build_dashscope_rag_vectors(
        data_root,
        embedding_provider=provider,
        output_path=output,
        batch_size=batch_size,
    )
    return {
        "status": "ok",
        "path": str(target),
        "model": provider.model,
        "revision": provider.revision,
        "dimension": provider.dimension,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=ROOT / "datasets" / "meguri")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--batch-size", type=int, default=10)
    args = parser.parse_args()
    result = asyncio.run(run(args.data_root, args.output, args.batch_size))
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
