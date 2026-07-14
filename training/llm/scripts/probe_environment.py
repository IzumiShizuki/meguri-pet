from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
import traceback
from pathlib import Path
from typing import Any

from .common import (
    ARTIFACT_ROOT,
    CONFIG_ROOT,
    PROJECT_ROOT,
    PipelineError,
    git_commit,
    load_yaml,
    package_versions,
    utc_now,
    write_json,
)
from .modeling import assert_text_only_trainable_parameters, load_model_with_lora


REQUIRED_PACKAGES = (
    "torch",
    "transformers",
    "trl",
    "peft",
    "datasets",
    "unsloth",
    "bitsandbytes",
    "jsonschema",
    "pydantic",
    "PyYAML",
    "safetensors",
    "huggingface-hub",
)


def validate_config(config: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    model = config.get("model")
    training = config.get("training")
    hardware = config.get("hardware")
    if not isinstance(model, dict) or not isinstance(training, dict) or not isinstance(hardware, dict):
        return ["config requires model, training, and hardware objects"]
    for field in ("repo_id", "revision", "tokenizer_repo_id", "tokenizer_revision", "loader"):
        if not model.get(field):
            errors.append(f"model.{field} is required")
    for field in ("revision", "tokenizer_revision"):
        value = str(model.get(field) or "")
        if len(value) != 40 or any(char not in "0123456789abcdef" for char in value.lower()):
            errors.append(f"model.{field} must be a pinned 40-character commit SHA")
    if int(training.get("max_seq_length") or 0) != 2048:
        errors.append("training.max_seq_length must start at 2048")
    if training.get("packing") is not False:
        errors.append("training.packing must be false for the initial probe")
    if training.get("assistant_only_loss") is not True:
        errors.append("training.assistant_only_loss must be true")
    if model.get("loader") == "unsloth_vision" and model.get("train_vision_layers") is not False:
        errors.append("Qwen3.5 vision layers must be frozen")
    if config.get("experiment_family") == "qwen3_8b_qlora" and config.get("enabled") is not False:
        errors.append("the 8B route must remain disabled until all enablement gates pass")
    return errors


def nvidia_smi() -> dict[str, Any]:
    command = [
        "nvidia-smi",
        "--query-gpu=name,memory.total,memory.used,memory.free,driver_version",
        "--format=csv,noheader,nounits",
    ]
    try:
        output = subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        return {"available": False, "error": type(exc).__name__}
    rows = []
    for line in output.splitlines():
        values = [item.strip() for item in line.split(",")]
        if len(values) == 5:
            rows.append(
                {
                    "name": values[0],
                    "memory_total_mib": int(values[1]),
                    "memory_used_mib": int(values[2]),
                    "memory_free_mib": int(values[3]),
                    "driver_version": values[4],
                }
            )
    return {"available": bool(rows), "gpus": rows}


def static_probe(config: dict[str, Any]) -> dict[str, Any]:
    config_errors = validate_config(config)
    versions = package_versions(REQUIRED_PACKAGES)
    missing = sorted(name for name, version in versions.items() if version is None)
    gpu = nvidia_smi()
    hardware_errors: list[str] = []
    if not gpu.get("available"):
        hardware_errors.append("nvidia-smi did not report a GPU")
    else:
        first = gpu["gpus"][0]
        expected = str(config["hardware"].get("expected_gpu_contains") or "")
        if expected and expected.lower() not in first["name"].lower():
            hardware_errors.append(f"expected GPU containing {expected!r}, got {first['name']!r}")
        minimum_mib = float(config["hardware"].get("minimum_total_vram_gib") or 0) * 1024
        if first["memory_total_mib"] < minimum_mib:
            hardware_errors.append("GPU total VRAM is below the configured minimum")
    errors = config_errors + hardware_errors
    if missing:
        errors.append("missing packages: " + ", ".join(missing))
    return {
        "status": "pass" if not errors else "fail",
        "errors": errors,
        "python": {"version": sys.version, "executable": sys.executable},
        "platform": {"system": platform.system(), "release": platform.release(), "version": platform.version()},
        "packages": versions,
        "gpu": gpu,
    }


def _text_tokenizer(processor: Any) -> Any:
    return getattr(processor, "tokenizer", processor)


def full_probe(config: dict[str, Any], *, allow_download: bool, artifact_dir: Path) -> dict[str, Any]:
    try:
        import torch
        from peft import PeftModel
    except ImportError as exc:
        raise PipelineError("full probe dependencies are unavailable") from exc
    if not torch.cuda.is_available():
        raise PipelineError("CUDA is unavailable")
    if bool(config["hardware"].get("require_bf16")) and not torch.cuda.is_bf16_supported():
        raise PipelineError("BF16 is required but unsupported")

    torch.cuda.empty_cache()
    torch.cuda.reset_peak_memory_stats()
    model, processor, model_api = load_model_with_lora(config, allow_download=allow_download)
    parameter_counts = assert_text_only_trainable_parameters(model)
    tokenizer = _text_tokenizer(processor)
    system_prompt = (PROJECT_ROOT / "configs" / "meguri_system_prompt.txt").read_text(encoding="utf-8")
    assistant = json.dumps(
        {
            "reply": "了解了，我们先确认最小训练链路。",
            "expression_tag": "neutral",
            "expression_intensity": "low",
            "voice_style": "neutral",
            "memory_candidates": [],
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "请确认训练环境。"},
        {"role": "assistant", "content": assistant},
    ]
    prompt = tokenizer.apply_chat_template(messages[:-1], tokenize=False, add_generation_prompt=True)
    complete = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=False)
    prompt_ids = tokenizer(prompt, add_special_tokens=False)["input_ids"]
    encoded = tokenizer(complete, add_special_tokens=False, return_tensors="pt")
    input_ids = encoded["input_ids"].to("cuda")
    attention_mask = encoded["attention_mask"].to("cuda")
    labels = input_ids.clone()
    labels[:, : len(prompt_ids)] = -100
    if not bool((labels != -100).any()):
        raise PipelineError("assistant-only mask contains no trainable tokens")

    model.train()
    output = model(input_ids=input_ids, attention_mask=attention_mask, labels=labels)
    if output.loss is None or not torch.isfinite(output.loss):
        raise PipelineError("single-batch forward did not produce a finite loss")
    output.loss.backward()
    model.zero_grad(set_to_none=True)

    adapter_dir = artifact_dir / "probe_adapter"
    if adapter_dir.exists():
        raise PipelineError(f"probe adapter path already exists: {adapter_dir}")
    model.save_pretrained(adapter_dir)
    if hasattr(processor, "save_pretrained"):
        processor.save_pretrained(adapter_dir)
    base_model = getattr(model, "get_base_model", lambda: model)()
    reloaded = PeftModel.from_pretrained(base_model, adapter_dir, is_trainable=False)
    if reloaded is None:
        raise PipelineError("adapter reload returned no model")

    peak_gib = torch.cuda.max_memory_allocated() / (1024**3)
    maximum = float(config["hardware"].get("maximum_training_peak_gib") or 0)
    if maximum and peak_gib > maximum:
        raise PipelineError(f"probe peak memory {peak_gib:.3f} GiB exceeds {maximum:.3f} GiB")
    return {
        "status": "pass",
        "cuda_version": torch.version.cuda,
        "torch_version": torch.__version__,
        "bf16_supported": torch.cuda.is_bf16_supported(),
        "device_name": torch.cuda.get_device_name(0),
        "loss": float(output.loss.detach().cpu()),
        "peak_memory_gib": round(peak_gib, 4),
        "assistant_mask": {
            "total_tokens": int(input_ids.shape[-1]),
            "prompt_tokens": len(prompt_ids),
            "assistant_tokens": int((labels != -100).sum().item()),
        },
        "adapter_saved": True,
        "adapter_reloaded": True,
        **parameter_counts,
        "model_api": getattr(model_api, "__name__", str(model_api)),
    }


def run_probe(
    config_path: Path,
    *,
    mode: str,
    allow_download: bool,
    report_path: Path,
) -> tuple[int, dict[str, Any]]:
    config = load_yaml(config_path)
    report: dict[str, Any] = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "git_commit": git_commit(),
        "config_path": str(config_path.resolve()),
        "experiment_family": config.get("experiment_family"),
        "model": config.get("model"),
        "mode": mode,
        "allow_download": allow_download,
    }
    static = static_probe(config)
    report["static"] = static
    exit_code = 0
    if static["status"] != "pass":
        report["status"] = "fail"
        report["full"] = {"status": "not_run", "reason": "static probe failed"}
        exit_code = 2
    elif mode == "full":
        artifact_dir = report_path.parent / (report_path.stem + "_artifacts")
        artifact_dir.mkdir(parents=True, exist_ok=False)
        try:
            report["full"] = full_probe(config, allow_download=allow_download, artifact_dir=artifact_dir)
            report["status"] = "pass"
        except Exception as exc:
            report["status"] = "fail"
            report["full"] = {
                "status": "fail",
                "error_type": type(exc).__name__,
                "error": str(exc),
                "traceback": traceback.format_exc(),
            }
            exit_code = 2
    else:
        report["status"] = "pass"
        report["full"] = {"status": "not_run", "reason": "static mode"}
    write_json(report_path, report)
    return exit_code, report


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe the exact Meguri text-LLM training environment")
    parser.add_argument(
        "--config",
        type=Path,
        default=CONFIG_ROOT / "qwen35_4b_bf16_lora.yaml",
    )
    parser.add_argument("--mode", choices=("static", "full"), default="static")
    parser.add_argument(
        "--allow-download",
        action="store_true",
        help="Allow fetching only the pinned model revision. No fallback is permitted.",
    )
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.allow_download and args.mode != "full":
        parser.error("--allow-download is valid only with --mode full")
    report_path = args.report or (
        ARTIFACT_ROOT / "reports" / f"environment_probe_{args.mode}_{utc_now().replace(':', '-')}.json"
    )
    if report_path.exists():
        raise PipelineError(f"refusing to overwrite existing report: {report_path}")
    exit_code, report = run_probe(
        args.config,
        mode=args.mode,
        allow_download=args.allow_download,
        report_path=report_path,
    )
    print(json.dumps({"status": report["status"], "report": str(report_path)}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())

