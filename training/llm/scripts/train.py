from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

from services.meguri_core.schemas import LlmResponse
from training.llm.scripts.common import (
    ARTIFACT_ROOT,
    CONFIG_ROOT,
    PipelineError,
    load_yaml,
    package_versions,
    read_jsonl,
    require_clean_git_worktree,
    sha256_file,
    sha256_text,
    utc_now,
    write_json,
)
from training.llm.scripts.modeling import (
    assert_text_only_trainable_parameters,
    autocast_dtype,
    configure_compile_cache,
    load_model_with_lora,
)
from training.llm.scripts.training_utils import (
    EXPERIMENT_ID,
    deterministic_stratified_subset,
    smoke_manifest,
    token_normalized_causal_lm_loss,
    tokenize_assistant_only,
    validate_dataset_for_training,
    validate_enablement_gate_report,
    validate_input_padding,
    validate_probe_report,
    validate_smoke_report,
    validate_training_peak_memory,
    validate_training_config,
)


def _last_checkpoint(checkpoint_root: Path) -> str | None:
    values: list[tuple[int, Path]] = []
    for path in checkpoint_root.glob("checkpoint-*"):
        try:
            values.append((int(path.name.split("-")[-1]), path))
        except ValueError:
            continue
    return str(max(values)[1].resolve()) if values else None


def _smoke_inference(
    model: Any,
    tokenizer: Any,
    row: dict[str, Any],
    *,
    generation_dtype: Any,
    max_new_tokens: int = 256,
) -> dict[str, Any]:
    import torch

    messages = row["messages"][:-1]
    rendered = tokenizer.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=False,
    )
    encoded = tokenizer(rendered, return_tensors="pt", add_special_tokens=False)
    encoded = {name: value.to("cuda") for name, value in encoded.items()}
    input_length = int(encoded["input_ids"].shape[-1])
    model.eval()
    with torch.inference_mode(), torch.autocast(device_type="cuda", dtype=generation_dtype):
        output = model.generate(
            **encoded,
            do_sample=False,
            max_new_tokens=max_new_tokens,
            pad_token_id=tokenizer.eos_token_id,
        )
    raw = tokenizer.decode(output[0][input_length:], skip_special_tokens=True).strip()
    try:
        parsed = json.loads(raw)
        LlmResponse.model_validate(parsed)
    except Exception as exc:
        raise PipelineError("post-training smoke inference did not produce valid response JSON") from exc
    return {"status": "pass", "raw_output_sha256": sha256_text(raw)}


def run(args: argparse.Namespace) -> Path:
    if not EXPERIMENT_ID.fullmatch(args.experiment_id):
        raise PipelineError("experiment ID must be a safe 3-80 character identifier")
    training_commit = require_clean_git_worktree()
    config = load_yaml(args.config)
    validate_enablement_gate_report(args.enablement_gate_report, config)
    validate_training_config(config, allow_disabled=args.enablement_gate_report is not None)
    probe = validate_probe_report(args.probe_report, config)
    manifest, _ = validate_dataset_for_training(args.dataset_dir)
    if args.smoke:
        if args.smoke_report is not None:
            raise PipelineError("L-006 smoke must not consume a prior smoke report")
        smoke_gate = None
    else:
        if args.smoke_report is None:
            raise PipelineError("full training requires --smoke-report from a passing L-006 run")
        smoke_gate = validate_smoke_report(
            args.smoke_report,
            config=config,
            dataset_manifest=manifest,
            training_config_sha256=sha256_file(args.config),
        )
    output_dir = args.experiment_root.resolve() / args.experiment_id
    resume_checkpoint = args.resume_from_checkpoint.resolve() if args.resume_from_checkpoint else None
    if resume_checkpoint is None and output_dir.exists():
        raise PipelineError(f"refusing to overwrite experiment: {output_dir}")
    if resume_checkpoint is not None:
        try:
            resume_checkpoint.relative_to(output_dir)
        except ValueError as exc:
            raise PipelineError("resume checkpoint must belong to the same experiment directory") from exc
        if not (resume_checkpoint / "trainer_state.json").is_file():
            raise PipelineError("resume checkpoint has no trainer_state.json")

    train_rows = [row for _, row in read_jsonl(args.dataset_dir / "train.jsonl")]
    validation_rows = [row for _, row in read_jsonl(args.dataset_dir / "validation.jsonl")]
    training = config["training"]
    seed = int(training["seed"])
    if args.smoke:
        train_rows = deterministic_stratified_subset(train_rows, size=args.smoke_samples, seed=seed)
        validation_rows = deterministic_stratified_subset(
            validation_rows,
            size=min(args.smoke_validation_samples, len(validation_rows)),
            seed=seed + 1,
        )
    configure_compile_cache()
    try:
        import torch
    except ImportError as exc:
        raise PipelineError("the pinned Unsloth/TRL training environment is incomplete") from exc

    torch.manual_seed(seed)
    model, processor, _ = load_model_with_lora(config, allow_download=args.allow_download)
    # Loading Unsloth first is required for its Transformers and PEFT patches.
    try:
        from datasets import Dataset
        from transformers import DataCollatorForSeq2Seq
        from trl import SFTConfig, SFTTrainer
    except ImportError as exc:
        raise PipelineError("the pinned Unsloth/TRL training environment is incomplete") from exc
    parameter_counts = assert_text_only_trainable_parameters(model)
    tokenizer = getattr(processor, "tokenizer", processor)
    max_length = int(training["max_seq_length"])
    encoded_train = [tokenize_assistant_only(row, tokenizer, max_seq_length=max_length) for row in train_rows]
    encoded_validation = [
        tokenize_assistant_only(row, tokenizer, max_seq_length=max_length) for row in validation_rows
    ]
    input_padding = validate_input_padding(
        input_pad_length=args.input_pad_length,
        max_seq_length=max_length,
        train_lengths=[len(row["input_ids"]) for row in encoded_train],
        validation_lengths=[len(row["input_ids"]) for row in encoded_validation],
        required=args.smoke,
    )
    output_dir.mkdir(parents=True, exist_ok=resume_checkpoint is not None)
    if args.smoke and not (output_dir / "smoke_dataset_manifest.json").exists():
        write_json(
            output_dir / "smoke_dataset_manifest.json",
            smoke_manifest(
                train_rows,
                validation_rows,
                dataset_id=str(manifest["dataset_id"]),
                seed=seed,
                input_padding=input_padding,
            ),
        )
    template = str(getattr(tokenizer, "chat_template", "") or "")
    if not template:
        raise PipelineError("pinned tokenizer has no chat template")
    kwargs: dict[str, Any] = {
        "output_dir": str(output_dir),
        "max_length": max_length,
        "packing": bool(training["packing"]),
        "per_device_train_batch_size": int(training["per_device_train_batch_size"]),
        "per_device_eval_batch_size": int(training["per_device_eval_batch_size"]),
        "gradient_accumulation_steps": int(training["gradient_accumulation_steps"]),
        "learning_rate": float(training["learning_rate"]),
        "num_train_epochs": float(training["num_train_epochs"]),
        "warmup_ratio": float(training["warmup_ratio"]),
        "weight_decay": float(training["weight_decay"]),
        "lr_scheduler_type": str(training["lr_scheduler_type"]),
        "logging_steps": int(training["logging_steps"]),
        "eval_strategy": "steps",
        "eval_steps": int(training["eval_steps"]),
        "save_strategy": "steps",
        "save_steps": int(training["save_steps"]),
        "save_total_limit": int(training["save_total_limit"]),
        "seed": seed,
        "data_seed": seed,
        "bf16": True,
        "fp16": False,
        "optim": str(training["optimizer"]),
        "report_to": "none",
        "load_best_model_at_end": False,
        "remove_unused_columns": False,
        "dataset_kwargs": {"skip_prepare_dataset": True},
    }
    if args.smoke:
        kwargs["max_steps"] = args.smoke_steps
        kwargs["eval_steps"] = min(int(training["eval_steps"]), max(1, args.smoke_steps // 2))
        kwargs["save_steps"] = min(int(training["save_steps"]), max(1, args.smoke_steps // 2))
    sft_args = SFTConfig(**kwargs)
    collator = DataCollatorForSeq2Seq(
        tokenizer=tokenizer,
        padding="max_length" if input_padding["enabled"] else True,
        max_length=input_padding.get("input_pad_length"),
        label_pad_token_id=-100,
        return_tensors="pt",
    )
    trainer = SFTTrainer(
        model=model,
        args=sft_args,
        train_dataset=Dataset.from_list(encoded_train),
        eval_dataset=Dataset.from_list(encoded_validation),
        processing_class=tokenizer,
        data_collator=collator,
        compute_loss_func=token_normalized_causal_lm_loss,
    )
    torch.cuda.empty_cache()
    torch.cuda.reset_peak_memory_stats()
    started_at = utc_now()
    start = time.perf_counter()
    try:
        result = trainer.train(resume_from_checkpoint=str(resume_checkpoint) if resume_checkpoint else None)
    except torch.cuda.OutOfMemoryError as exc:
        failure = {
            "schema_version": 1,
            "experiment_id": args.experiment_id,
            "status": "fail",
            "reason": "cuda_out_of_memory",
            "peak_vram_bytes": int(torch.cuda.max_memory_allocated()),
            "generated_at": utc_now(),
        }
        if not (output_dir / "failure_report.json").exists():
            write_json(output_dir / "failure_report.json", failure)
        raise PipelineError("training stopped on CUDA OOM; no data or model fallback was applied") from exc
    duration = time.perf_counter() - start
    peak_vram_bytes = int(torch.cuda.max_memory_allocated())
    validate_training_peak_memory(peak_vram_bytes, config)
    trainer.save_state()
    final_adapter = output_dir / "final_adapter"
    if final_adapter.exists():
        raise PipelineError(f"refusing to overwrite final adapter: {final_adapter}")
    model.save_pretrained(final_adapter)
    tokenizer.save_pretrained(final_adapter)
    smoke_result = _smoke_inference(
        model,
        tokenizer,
        validation_rows[0],
        generation_dtype=autocast_dtype(config, torch),
    )
    if require_clean_git_worktree() != training_commit:
        raise PipelineError("Git commit changed while training was running")
    experiment = {
        "schema_version": 1,
        "experiment_id": args.experiment_id,
        "experiment_family": config["experiment_family"],
        "stage": "L1_smoke" if args.smoke else "full_training",
        "status": "pass",
        "base_model_repo": config["model"]["repo_id"],
        "base_model_revision": config["model"]["revision"],
        "tokenizer_revision": config["model"]["tokenizer_revision"],
        "dataset_id": manifest["dataset_id"],
        "data_build_id": manifest["source_build_id"],
        "dataset_manifest_sha256": sha256_file(args.dataset_dir / "dataset_manifest.json"),
        "prompt_sha256": manifest["prompt_sha256"],
        "response_schema_sha256": manifest["response_schema_sha256"],
        "chat_template_sha256": sha256_text(template),
        "training_commit": training_commit,
        "training_config": str(args.config.resolve()),
        "training_config_sha256": sha256_file(args.config),
        "probe_report_sha256": sha256_file(args.probe_report),
        "probe_peak_memory_gib": probe["full"]["peak_memory_gib"],
        "framework_versions": package_versions(
            ["torch", "transformers", "trl", "peft", "datasets", "unsloth", "bitsandbytes"]
        ),
        "seed": seed,
        "lora": config["lora"],
        "training_parameters": kwargs,
        "loss_normalization": "assistant_tokens_across_gradient_accumulation",
        "input_padding": input_padding,
        "train_samples": len(train_rows),
        "validation_samples": len(validation_rows),
        "train_metrics": result.metrics,
        "parameter_counts": parameter_counts,
        "peak_vram_bytes": peak_vram_bytes,
        "started_at": started_at,
        "finished_at": utc_now(),
        "duration_seconds": round(duration, 3),
        "best_checkpoint": None,
        "best_checkpoint_policy": "selected later by frozen validation composite score; never by locked eval",
        "last_resumable_checkpoint": _last_checkpoint(output_dir),
        "final_adapter": str(final_adapter.resolve()),
        "post_training_json_smoke": smoke_result,
        "locked_eval_accessed": False,
        "smoke_gate_report_sha256": sha256_file(args.smoke_report) if smoke_gate else None,
    }
    write_json(output_dir / "experiment_manifest.json", experiment)
    return output_dir


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description="Run reproducible Meguri LoRA/QLoRA SFT")
    value.add_argument("--experiment-id", required=True)
    value.add_argument("--config", type=Path, default=CONFIG_ROOT / "qwen35_4b_bf16_lora.yaml")
    value.add_argument("--dataset-dir", type=Path, required=True)
    value.add_argument("--probe-report", type=Path, required=True)
    value.add_argument("--smoke-report", type=Path)
    value.add_argument("--experiment-root", type=Path, default=ARTIFACT_ROOT / "checkpoints")
    value.add_argument("--allow-download", action="store_true")
    value.add_argument("--enablement-gate-report", type=Path)
    value.add_argument("--smoke", action="store_true")
    value.add_argument("--smoke-samples", type=int, default=160)
    value.add_argument("--smoke-validation-samples", type=int, default=40)
    value.add_argument("--smoke-steps", type=int, default=75)
    value.add_argument("--input-pad-length", type=int)
    value.add_argument("--resume-from-checkpoint", type=Path)
    return value


def main() -> int:
    try:
        output = run(parser().parse_args())
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "experiment_dir": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
