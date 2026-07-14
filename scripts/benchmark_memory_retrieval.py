from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.meguri_core.memory_service.benchmark import (
    run_synthetic_exact_ann_benchmark,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run deterministic memory recall baseline")
    parser.add_argument("--corpus-size", type=int, default=500)
    parser.add_argument("--queries", type=int, default=40)
    parser.add_argument("--dimension", type=int, default=1024)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--seed", type=int, default=20260714)
    arguments = parser.parse_args()
    result = run_synthetic_exact_ann_benchmark(
        corpus_size=arguments.corpus_size,
        query_count=arguments.queries,
        dimension=arguments.dimension,
        top_k=arguments.top_k,
        seed=arguments.seed,
    )
    print(json.dumps(result.model_dump(mode="json"), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
