from __future__ import annotations

import argparse
import gc
import json
import os
import platform
import subprocess
import sys
import traceback
from pathlib import Path
from typing import Any

from services.meguri_core.schemas import LlmResponse

from .common import (
    ARTIFACT_ROOT,
    CONFIG_ROOT,
    PROJECT_ROOT,
    PipelineError,
    git_commit,
    load_yaml,
    package_versions,
    sha256_text,
    utc_now,
    write_json,
)
from .modeling import assert_text_only_trainable_parameters, load_base_model, load_model_with_lora
from .training_utils import tokenize_assistant_only


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
    required = set(REQUIRED_PACKAGES)
    if not config.get("model", {}).get("load_in_4bit"):
        required.discard("bitsandbytes")
    missing = sorted(name for name in required if versions.get(name) is None)
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
    system_prompt = (PROJECT_ROOT / "configs" / "meguri_system_prompt.txt").read_text(encoding="utf-8").strip()
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
    row = {
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": "请确认训练环境。"},
            {"role": "assistant", "content": assistant},
        ]
    }
    encoded = tokenize_assistant_only(
        row,
        tokenizer,
        max_seq_length=int(config["training"]["max_seq_length"]),
    )
    input_ids = torch.tensor([encoded["input_ids"]], device="cuda")
    attention_mask = torch.tensor([encoded["attention_mask"]], device="cuda")
    labels = torch.tensor([encoded["labels"]], device="cuda")

    model.train()
    output = model(input_ids=input_ids, attention_mask=attention_mask, labels=labels)
    if output.loss is None or not torch.isfinite(output.loss):
        raise PipelineError("single-batch forward did not produce a finite loss")
    output.loss.backward()
    model.zero_grad(set_to_none=True)
    loss_value = float(output.loss.detach().cpu())
    assistant_mask = {
        "total_tokens": len(encoded["input_ids"]),
        "prompt_tokens": sum(value == -100 for value in encoded["labels"]),
        "assistant_tokens": sum(value != -100 for value in encoded["labels"]),
    }
    gradient_checkpointing = bool(getattr(model, "is_gradient_checkpointing", False))
    if not gradient_checkpointing:
        raise PipelineError("gradient checkpointing is not active after LoRA attachment")

    adapter_dir = artifact_dir / "probe_adapter"
    if adapter_dir.exists():
        raise PipelineError(f"probe adapter path already exists: {adapter_dir}")
    model.save_pretrained(adapter_dir)
    if hasattr(processor, "save_pretrained"):
        processor.save_pretrained(adapter_dir)
    peak_gib = torch.cuda.max_memory_allocated() / (1024**3)
    del output, input_ids, attention_mask, labels, model
    gc.collect()
    torch.cuda.empty_cache()
    base_model, reloaded_processor, reloaded_api = load_base_model(config, allow_download=False)
    reloaded = PeftModel.from_pretrained(base_model, adapter_dir, is_trainable=False)
    if reloaded is None:
        raise PipelineError("adapter reload returned no model")
    if hasattr(reloaded_api, "for_inference"):
        reloaded_api.for_inference(reloaded)
    inference_tokenizer = _text_tokenizer(reloaded_processor)
    inference_messages = [
        {"role": "system", "content": system_prompt},
        {
            "role": "user",
            "content": json.dumps(
                {
                    "runtime_state": {
                        "client_id": "website",
                        "mode": "work",
                        "relationship_profile": "sibling",
                        "outfit_code": "01",
                        "local_time": "2026-07-14T12:00:00+08:00",
                        "is_holiday": False,
                        "voice_enabled": False,
                        "screen_context_enabled": False,
                        "allowed_expression_tags": ["neutral", "happy", "worried"],
                    },
                    "user_message": "请用 JSON 确认环境正常。",
                    "canon_examples": [],
                    "long_term_memories": [],
                    "recent_context": [],
                },
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        },
    ]
    rendered = inference_tokenizer.apply_chat_template(
        inference_messages,
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=False,
    )
    inference_inputs = inference_tokenizer(rendered, return_tensors="pt", add_special_tokens=False)
    inference_inputs = {name: value.to("cuda") for name, value in inference_inputs.items()}
    inference_length = int(inference_inputs["input_ids"].shape[-1])
    with torch.inference_mode():
        generated = reloaded.generate(
            **inference_inputs,
            do_sample=False,
            max_new_tokens=256,
            pad_token_id=inference_tokenizer.eos_token_id,
        )
    raw_inference = inference_tokenizer.decode(
        generated[0][inference_length:], skip_special_tokens=True
    ).strip()
    try:
        LlmResponse.model_validate(json.loads(raw_inference))
    except Exception as exc:
        raise PipelineError("minimum probe inference did not produce valid Meguri JSON") from exc
    peak_gib = max(peak_gib, torch.cuda.max_memory_allocated() / (1024**3))
    maximum = float(config["hardware"].get("maximum_training_peak_gib") or 0)
    if maximum and peak_gib > maximum:
        raise PipelineError(f"probe peak memory {peak_gib:.3f} GiB exceeds {maximum:.3f} GiB")
    return {
        "status": "pass",
        "cuda_version": torch.version.cuda,
        "torch_version": torch.__version__,
        "bf16_supported": torch.cuda.is_bf16_supported(),
        "device_name": torch.cuda.get_device_name(0),
        "loss": loss_value,
        "peak_memory_gib": round(peak_gib, 4),
        "checks": {
            "cuda_available": True,
            "bf16_supported": True,
            "model_loaded": True,
            "assistant_mask": True,
            "forward": True,
            "backward": True,
            "gradient_checkpointing": gradient_checkpointing,
            "adapter_save": True,
            "adapter_reload": True,
            "json_inference": True,
        },
        "assistant_mask": assistant_mask,
        "adapter_saved": True,
        "adapter_reloaded": True,
        "minimum_json_inference_sha256": sha256_text(raw_inference),
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
