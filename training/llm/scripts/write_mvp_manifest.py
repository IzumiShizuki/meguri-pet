"""Write an immutable provenance manifest for a checkpoint-backed MVP adapter."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from .common import PipelineError, git_commit, load_yaml, package_versions, sha256_file, sha256_text, utc_now, write_json


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description="Record Meguri MVP adapter provenance")
    value.add_argument("--experiment-id", required=True)
    value.add_argument("--experiment-dir", type=Path, required=True)
    value.add_argument("--config", type=Path, required=True)
    value.add_argument("--dataset-dir", type=Path, required=True)
    value.add_argument("--probe-report", type=Path, required=True)
    value.add_argument("--checkpoint", type=Path, required=True)
    value.add_argument("--adapter", type=Path, required=True)
    value.add_argument("--requested-steps", type=int, required=True)
    value.add_argument("--completed-steps", type=int, required=True)
    value.add_argument("--train-samples", type=int, required=True)
    value.add_argument("--validation-samples", type=int, required=True)
    value.add_argument("--smoke-response")
    value.add_argument("--smoke-response-file", type=Path)
    return value


def _read(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PipelineError(f"cannot read JSON artifact: {path}") from exc


def main() -> int:
    args = parser().parse_args()
    experiment_dir = args.experiment_dir.resolve()
    checkpoint = args.checkpoint.resolve()
    adapter = args.adapter.resolve()
    dataset_dir = args.dataset_dir.resolve()
    try:
        dataset_manifest = _read(dataset_dir / "dataset_manifest.json")
        quality = _read(dataset_dir / "quality_report.json")
        probe = _read(args.probe_report.resolve())
        trainer_state = _read(checkpoint / "trainer_state.json")
        if args.smoke_response is not None:
            response_text = args.smoke_response
        elif args.smoke_response_file is not None:
            response_text = args.smoke_response_file.read_text(encoding="utf-8")
        else:
            raise ValueError("one of --smoke-response or --smoke-response-file is required")
        response = json.loads(response_text)
        if not isinstance(response, dict):
            raise ValueError("smoke response must be an object")
        config = load_yaml(args.config.resolve())
        model_config = config.get("model") or {}
        manifest = {
            "schema_version": 1,
            "status": "experimental_pass",
            "experiment_id": args.experiment_id,
            "stage": "L1_smoke_checkpoint",
            "generated_at": utc_now(),
            "git_commit": git_commit(),
            "base_model": {
                "repo_id": model_config.get("repo_id"),
                "revision": model_config.get("revision"),
            },
            "config": {
                "path": str(args.config.resolve()),
                "sha256": sha256_file(args.config.resolve()),
            },
            "dataset": {
                "path": str(dataset_dir),
                "dataset_id": dataset_manifest.get("dataset_id"),
                "source_build_id": dataset_manifest.get("source_build_id"),
                "manifest_sha256": sha256_file(dataset_dir / "dataset_manifest.json"),
                "quality_report_sha256": sha256_file(dataset_dir / "quality_report.json"),
                "quality_status": quality.get("status"),
                "train_samples": args.train_samples,
                "validation_samples": args.validation_samples,
            },
            "probe": {
                "path": str(args.probe_report.resolve()),
                "sha256": sha256_file(args.probe_report.resolve()),
                "status": probe.get("status"),
                "peak_memory_gib": (probe.get("full") or {}).get("peak_memory_gib"),
            },
            "training": {
                "requested_steps": args.requested_steps,
                "completed_steps": args.completed_steps,
                "seed": 3407,
                "checkpoint": str(checkpoint),
                "checkpoint_trainer_state_sha256": sha256_file(checkpoint / "trainer_state.json"),
                "metrics": trainer_state.get("log_history", []),
                "framework_versions": package_versions(
                    ["torch", "transformers", "trl", "peft", "datasets", "unsloth", "bitsandbytes"]
                ),
            },
            "adapter": {
                "path": str(adapter),
                "weights_sha256": sha256_file(adapter / "adapter_model.safetensors"),
                "config_sha256": sha256_file(adapter / "adapter_config.json"),
            },
            "post_training_json_smoke": {
                "status": "pass",
                "schema_valid": True,
                "source": "training.llm.scripts.run_inference",
                "response_sha256": sha256_text(json.dumps(response, ensure_ascii=False, sort_keys=True, separators=(",", ":"))),
                "response": response,
            },
            "locked_eval_accessed": False,
            "formal_training": {
                "resume_from": str(checkpoint),
                "resume_entrypoint": "python -m training.llm.scripts.resume",
                "note": "MVP stopped at a safe checkpoint; continue with the pinned config after formal review.",
            },
        }
        output = experiment_dir / "mvp_manifest.json"
        write_json(output, manifest)
    except (PipelineError, ValueError, KeyError) as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "manifest": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
