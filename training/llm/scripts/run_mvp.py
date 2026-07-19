"""Run the smallest reproducible Meguri text-model MVP pipeline.

This wrapper intentionally keeps the formal route intact: it rebuilds only a
derived dataset, requires the pinned full probe, runs the existing smoke
trainer, and leaves locked evaluation and registry updates untouched.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

from .common import ARTIFACT_ROOT, CONFIG_ROOT, PROJECT_ROOT, PipelineError, default_data_root


def _runtime_env(*, allow_download: bool) -> dict[str, str]:
    env = dict(os.environ)
    # Keep the quick path deterministic and avoid Unsloth's generated cache
    # reintroducing the Windows BF16 Conv1d mismatch seen on this machine.
    env["UNSLOTH_COMPILE_DISABLE"] = "1"
    env["UNSLOTH_COMPILE_LOCATION"] = r"D:\environment\cache\meguri-llm"
    env["UNSLOTH_STUDIO_DISABLED"] = "1"
    if not allow_download:
        env["HF_HUB_OFFLINE"] = "1"
    return env


def _run(module: str, arguments: list[str], *, env: dict[str, str]) -> None:
    command = [sys.executable, "-m", module, *arguments]
    completed = subprocess.run(command, cwd=PROJECT_ROOT, env=env)
    if completed.returncode != 0:
        raise PipelineError(f"{module} failed with exit code {completed.returncode}")


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description="Run the reproducible Meguri text MVP fit")
    value.add_argument("--experiment-id", required=True)
    value.add_argument("--config", type=Path, default=CONFIG_ROOT / "qwen35_4b_bf16_lora.yaml")
    value.add_argument("--data-root", type=Path, default=default_data_root())
    value.add_argument(
        "--split-root",
        type=Path,
        default=PROJECT_ROOT / "data" / "meguri" / "aligned_v1" / "splits",
    )
    value.add_argument("--dataset-dir", type=Path)
    value.add_argument("--probe-report", type=Path)
    value.add_argument("--experiment-root", type=Path, default=ARTIFACT_ROOT / "checkpoints")
    value.add_argument("--smoke-samples", type=int, default=100)
    value.add_argument("--smoke-validation-samples", type=int, default=20)
    value.add_argument("--smoke-steps", type=int, default=50)
    value.add_argument("--input-pad-length", type=int, default=1152)
    value.add_argument("--allow-download", action="store_true")
    return value


def main() -> int:
    args = parser().parse_args()
    env = _runtime_env(allow_download=args.allow_download)
    dataset_dir = (
        args.dataset_dir.resolve()
        if args.dataset_dir
        else (ARTIFACT_ROOT / "datasets" / "meguri-text-sft-v1-532aca8b1a5d").resolve()
    )
    try:
        if not (dataset_dir / "train.jsonl").is_file() or not (dataset_dir / "validation.jsonl").is_file():
            _run(
                "training.llm.scripts.build_sft_dataset",
                [
                    "--data-root",
                    str(args.data_root.resolve()),
                    "--split-root",
                    str(args.split_root.resolve()),
                    "--output-root",
                    str(dataset_dir.parent),
                ],
                env=env,
            )
        _run(
            "training.llm.scripts.validate_sft_dataset",
            [str(dataset_dir), "--split-root", str(args.split_root.resolve())],
            env=env,
        )
        probe_report = (
            args.probe_report.resolve()
            if args.probe_report
            else ARTIFACT_ROOT / "reports" / f"{args.experiment_id}-full-probe.json"
        )
        if not probe_report.exists():
            probe_args = [
                "--config",
                str(args.config.resolve()),
                "--mode",
                "full",
                "--report",
                str(probe_report),
            ]
            if args.allow_download:
                probe_args.append("--allow-download")
            _run("training.llm.scripts.probe_environment", probe_args, env=env)
        else:
            try:
                probe_status = json.loads(probe_report.read_text(encoding="utf-8")).get("status")
            except (OSError, json.JSONDecodeError) as exc:
                raise PipelineError(f"cannot read existing probe report: {probe_report}") from exc
            if probe_status != "pass":
                raise PipelineError(f"existing probe report is not passing: {probe_report}")
        smoke_args = [
            "--experiment-id",
            args.experiment_id,
            "--config",
            str(args.config.resolve()),
            "--dataset-dir",
            str(dataset_dir),
            "--probe-report",
            str(probe_report),
            "--experiment-root",
            str(args.experiment_root.resolve()),
            "--smoke-samples",
            str(args.smoke_samples),
            "--smoke-validation-samples",
            str(args.smoke_validation_samples),
            "--smoke-steps",
            str(args.smoke_steps),
            "--input-pad-length",
            str(args.input_pad_length),
        ]
        if args.allow_download:
            smoke_args.append("--allow-download")
        _run("training.llm.scripts.run_smoke", smoke_args, env=env)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    output_dir = args.experiment_root.resolve() / args.experiment_id
    print(
        json.dumps(
            {
                "status": "pass",
                "experiment_dir": str(output_dir),
                "dataset_dir": str(dataset_dir),
                "probe_report": str(probe_report),
                "locked_eval_accessed": False,
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
