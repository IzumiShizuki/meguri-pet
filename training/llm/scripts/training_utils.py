from __future__ import annotations

import json
import random
import re
from collections import defaultdict
from pathlib import Path
from typing import Any

from training.llm.scripts.common import (
    PipelineError,
    canonical_json,
    read_json,
    sha256_file,
    sha256_text,
)


EXPERIMENT_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{2,79}$")


def validate_training_config(config: dict[str, Any], *, allow_disabled: bool = False) -> None:
    required = {"schema_version", "experiment_family", "enabled", "model", "training", "lora", "hardware"}
    missing = required - set(config)
    if missing:
        raise PipelineError(f"training config missing fields: {sorted(missing)}")
    if not config.get("enabled") and not allow_disabled:
        gates = config.get("enablement_gates") or []
        raise PipelineError(f"training config is disabled; unresolved gates: {gates}")
    model = config["model"]
    for field in ("repo_id", "revision", "tokenizer_repo_id", "tokenizer_revision", "loader"):
        if not str(model.get(field) or ""):
            raise PipelineError(f"training config model.{field} is required")
    if model.get("loader") == "unsloth_vision" and model.get("train_vision_layers") is not False:
        raise PipelineError("vision layers must remain frozen")
    if model.get("repo_id", "").startswith("Qwen/Qwen3.5") and model.get("load_in_4bit"):
        raise PipelineError("Qwen3.5 mainline must not silently switch to 4-bit QLoRA")
    training = config["training"]
    if training.get("assistant_only_loss") is not True:
        raise PipelineError("assistant_only_loss must be enabled")
    if int(training.get("max_seq_length", 0)) <= 0:
        raise PipelineError("max_seq_length must be positive")
    if int(training.get("per_device_train_batch_size", 0)) != 1:
        raise PipelineError("the pinned 16GB baseline requires per-device batch size 1")


def validate_probe_report(path: Path, config: dict[str, Any]) -> dict[str, Any]:
    report = read_json(path)
    if report.get("status") != "pass" or report.get("mode") != "full":
        raise PipelineError("a passing full L-001 probe report is required before training")
    expected = config["model"]
    identity = report.get("model") or {}
    for field in ("repo_id", "revision", "tokenizer_revision"):
        if identity.get(field) != expected.get(field):
            raise PipelineError(f"probe model identity mismatch: {field}")
    full = report.get("full") or {}
    if full.get("status") != "pass":
        raise PipelineError("the L-001 full probe section is not passing")
    checks = full.get("checks") or {}
    required = (
        "cuda_available",
        "bf16_supported",
        "model_loaded",
        "assistant_mask",
        "forward",
        "backward",
        "gradient_checkpointing",
        "adapter_save",
        "adapter_reload",
        "json_inference",
    )
    failed = [name for name in required if checks.get(name) is not True]
    if failed:
        raise PipelineError(f"full probe is missing passing checks: {failed}")
    return report


def validate_enablement_gate_report(path: Path | None, config: dict[str, Any]) -> None:
    if config.get("enabled"):
        if path is not None:
            raise PipelineError("enabled training configs must not use a disabled-route gate override")
        return
    if path is None:
        raise PipelineError("disabled training route requires an explicit enablement gate report")
    report = read_json(path)
    gates = report.get("gates") or {}
    required = list(config.get("enablement_gates") or [])
    missing = [name for name in required if gates.get(name) is not True]
    if missing:
        raise PipelineError(f"disabled-route enablement gates are not passing: {missing}")
    if not report.get("project_lead_approval_reference"):
        raise PipelineError("disabled-route gate report requires a project lead approval reference")


def validate_dataset_for_training(dataset_dir: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = read_json(dataset_dir / "dataset_manifest.json")
    quality = read_json(dataset_dir / "quality_report.json")
    if quality.get("status") != "pass" or manifest.get("quality_gate_status") != "pass":
        raise PipelineError("dataset quality gates are not passing")
    if manifest.get("train_count") != 2626 or manifest.get("validation_count") != 566:
        raise PipelineError("dataset counts do not match the approved 2626/566 baseline")
    policy = manifest.get("locked_eval_policy") or {}
    if any(policy.get(name) is not False for name in ("content_read_by_converter", "used_for_training", "used_for_prompt_tuning")):
        raise PipelineError("dataset manifest does not prove locked-eval isolation")
    for name in ("train.jsonl", "validation.jsonl", "quality_report.json"):
        expected = manifest.get("files", {}).get(name)
        actual_path = dataset_dir / name
        if name != "quality_report.json" and not actual_path.exists():
            raise PipelineError(f"derived dataset file is unavailable: {actual_path}")
        if actual_path.exists() and expected != sha256_file(actual_path):
            raise PipelineError(f"derived dataset hash mismatch: {name}")
    return manifest, quality


def deterministic_stratified_subset(
    rows: list[dict[str, Any]],
    *,
    size: int,
    seed: int,
) -> list[dict[str, Any]]:
    if size <= 0 or size > len(rows):
        raise PipelineError(f"subset size must be within 1..{len(rows)}")
    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        metadata = row.get("metadata") or {}
        key = (
            str(metadata.get("language")),
            str(metadata.get("relationship_stage")),
            str(metadata.get("interaction_mode")),
        )
        groups[key].append(row)
    for key, values in groups.items():
        values.sort(
            key=lambda row: sha256_text(
                f"{seed}:{key}:{(row.get('metadata') or {}).get('sample_id')}"
            )
        )
    selected: list[dict[str, Any]] = []
    keys = sorted(groups)
    cursor = 0
    while len(selected) < size:
        key = keys[cursor % len(keys)]
        if groups[key]:
            selected.append(groups[key].pop(0))
        cursor += 1
        if cursor > len(rows) * len(keys) + 1:
            raise PipelineError("cannot construct deterministic smoke subset")
    random.Random(seed).shuffle(selected)
    return selected


def tokenize_assistant_only(
    row: dict[str, Any],
    tokenizer: Any,
    *,
    max_seq_length: int,
) -> dict[str, list[int]]:
    messages = row.get("messages")
    if not isinstance(messages, list) or [item.get("role") for item in messages] != ["system", "user", "assistant"]:
        raise PipelineError("SFT row must contain exactly system, user, assistant messages")
    assistant_content = messages[-1].get("content")
    if not isinstance(assistant_content, str):
        raise PipelineError("assistant target must be a JSON string")
    try:
        json.loads(assistant_content)
    except json.JSONDecodeError as exc:
        raise PipelineError("assistant target JSON is invalid before tokenization") from exc
    if assistant_content.lstrip().startswith("```"):
        raise PipelineError("assistant target must not be Markdown wrapped")
    try:
        prefix = tokenizer.apply_chat_template(
            messages[:-1], tokenize=False, add_generation_prompt=True, enable_thinking=False
        )
        full = tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=False, enable_thinking=False
        )
    except Exception as exc:
        raise PipelineError("pinned tokenizer cannot render the assistant-only training template") from exc
    prefix_ids = tokenizer(prefix, add_special_tokens=False)["input_ids"]
    full_ids = tokenizer(full, add_special_tokens=False)["input_ids"]
    if full_ids[: len(prefix_ids)] != prefix_ids:
        raise PipelineError("chat template assistant boundary is not prefix-stable")
    if len(full_ids) > max_seq_length:
        raise PipelineError(
            f"tokenized sample exceeds max_seq_length without safe JSON truncation: {len(full_ids)}>{max_seq_length}"
        )
    eos_token_id = getattr(tokenizer, "eos_token_id", None)
    if eos_token_id is None or not full_ids:
        raise PipelineError("chat template does not expose an EOS token")
    # Qwen3.5 renders the assistant terminator as `<|im_end|>` followed by a
    # template newline.  Requiring the literal final token to be EOS rejects
    # that valid format even though the assistant content is properly closed.
    # Accept only whitespace after the final EOS so an arbitrary non-terminated
    # response cannot pass this guard.
    try:
        last_eos = len(full_ids) - 1 - full_ids[::-1].index(eos_token_id)
    except ValueError as exc:
        raise PipelineError("chat template does not terminate the assistant response with EOS") from exc
    trailing_ids = full_ids[last_eos + 1 :]
    if trailing_ids and tokenizer.decode(trailing_ids, skip_special_tokens=True).strip():
        raise PipelineError("chat template does not terminate the assistant response with EOS")
    labels = [-100] * len(prefix_ids) + full_ids[len(prefix_ids) :]
    if not any(label != -100 for label in labels) or any(label != -100 for label in labels[: len(prefix_ids)]):
        raise PipelineError("assistant-only loss mask is invalid")
    supervised = tokenizer.decode([label for label in labels if label != -100], skip_special_tokens=True)
    if assistant_content not in supervised:
        raise PipelineError("assistant-only mask does not preserve the complete JSON boundary")
    return {"input_ids": full_ids, "attention_mask": [1] * len(full_ids), "labels": labels}


def smoke_manifest(
    rows: list[dict[str, Any]],
    validation_rows: list[dict[str, Any]],
    *,
    dataset_id: str,
    seed: int,
) -> dict[str, Any]:
    ids = [str((row.get("metadata") or {}).get("sample_id")) for row in rows]
    validation_ids = [str((row.get("metadata") or {}).get("sample_id")) for row in validation_rows]
    return {
        "schema_version": 1,
        "parent_dataset_id": dataset_id,
        "selection": "deterministic_stratified_v1",
        "seed": seed,
        "train_count": len(ids),
        "validation_count": len(validation_ids),
        "sample_ids_sha256": sha256_text(canonical_json(ids)),
        "validation_sample_ids_sha256": sha256_text(canonical_json(validation_ids)),
        "locked_eval_accessed": False,
    }
