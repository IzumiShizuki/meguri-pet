from __future__ import annotations

import argparse
import json
from pathlib import Path

from .common import PipelineError
from .dataset import validate_dataset


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate a derived Meguri SFT dataset and its manifest")
    parser.add_argument("dataset_dir", type=Path)
    parser.add_argument("--split-root", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = validate_dataset(args.dataset_dir, split_root=args.split_root)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "pass" else 2


if __name__ == "__main__":
    raise SystemExit(main())

