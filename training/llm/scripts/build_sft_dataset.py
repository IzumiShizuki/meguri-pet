from __future__ import annotations

import argparse
import json
from pathlib import Path

from .common import ARTIFACT_ROOT, PipelineError, default_data_root
from .dataset import build_dataset


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the deterministic Meguri conversational SFT dataset")
    parser.add_argument("--data-root", type=Path, default=default_data_root())
    parser.add_argument(
        "--split-root",
        type=Path,
        required=True,
        help="Read-only aligned_v1/splits directory; only test_scene_ids.txt metadata is read.",
    )
    parser.add_argument("--output-root", type=Path, default=ARTIFACT_ROOT / "datasets")
    args = parser.parse_args()
    try:
        output = build_dataset(
            data_root=args.data_root,
            split_root=args.split_root,
            output_root=args.output_root,
        )
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "dataset_dir": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

