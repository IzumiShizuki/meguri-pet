from __future__ import annotations

import argparse
import json
from pathlib import Path

from training.llm.scripts.common import PipelineError, read_json, sha256_file, utc_now, write_json


def select(reports: list[Path], output: Path) -> dict:
    if len(reports) < 2:
        raise PipelineError("checkpoint selection requires at least two validation reports")
    candidates = []
    dataset_ids = set()
    config_hashes = set()
    for path in reports:
        report = read_json(path)
        if report.get("status") != "pass" or report.get("selection_eligible") is not True:
            raise PipelineError(f"validation report is not selection eligible: {path}")
        if report.get("locked_eval_accessed") is not False:
            raise PipelineError("locked-eval-influenced reports cannot select a checkpoint")
        dataset_ids.add(report.get("dataset_id"))
        config_hashes.add(report.get("provenance", {}).get("training_config_sha256"))
        candidates.append(
            {
                "report": str(path.resolve()),
                "report_sha256": sha256_file(path),
                "adapter_path": report["model"]["adapter_path"],
                "adapter_sha256": report["adapter_sha256"],
                "composite_score": float(report["composite"]["score"]),
            }
        )
    if len(dataset_ids) != 1 or len(config_hashes) != 1:
        raise PipelineError("checkpoint reports must use one dataset and one training config")
    candidates.sort(key=lambda item: (-item["composite_score"], item["adapter_sha256"]))
    selection = {
        "schema_version": 1,
        "status": "pass",
        "selection_policy": "highest frozen validation composite score; deterministic adapter-hash tie break",
        "locked_eval_used": False,
        "dataset_id": next(iter(dataset_ids)),
        "training_config_sha256": next(iter(config_hashes)),
        "selected": candidates[0],
        "candidates": candidates,
        "created_at": utc_now(),
    }
    write_json(output, selection)
    return selection


def main() -> int:
    parser = argparse.ArgumentParser(description="Select a best checkpoint without locked-eval leakage")
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = select(args.reports, args.output)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "selected": result["selected"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
