from __future__ import annotations

import argparse
import json
import os
import re
import statistics
from collections import Counter
from pathlib import Path
from typing import Any

from training.llm.eval.backends import LocalUnslothBackend, OpenAIBackend
from training.llm.eval.eval_cases import FrozenRag, case_request, frozen_prompt_contract, load_locked_cases
from training.llm.eval.memorization_eval import (
    aggregate_memorization_metrics,
    evaluate_memorization,
    load_training_reply_index,
)
from training.llm.eval.persona_eval import aggregate_persona_metrics, evaluate_persona
from training.llm.eval.schema_eval import aggregate_schema_metrics, evaluate_output
from training.llm.generation_profile import resolve_generation_settings
from training.llm.scripts.common import (
    ARTIFACT_ROOT,
    PipelineError,
    canonical_json,
    load_yaml,
    package_versions,
    read_json,
    require_clean_git_worktree,
    require_git_tracked_file,
    sha256_file,
    utc_now,
    write_json,
)


RUN_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{2,79}$")


def _percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * quantile)))
    return round(ordered[index], 3)


def _performance(rows: list[dict[str, Any]]) -> dict[str, Any]:
    inputs = [int(row["performance"]["input_tokens"]) for row in rows if row["performance"]["input_tokens"] is not None]
    first = [float(row["performance"]["first_token_latency_ms"]) for row in rows if row["performance"]["first_token_latency_ms"] is not None]
    total = [float(row["performance"]["total_latency_ms"]) for row in rows if row["performance"]["total_latency_ms"] is not None]
    speed = [float(row["performance"]["tokens_per_second"]) for row in rows if row["performance"]["tokens_per_second"] is not None]
    peaks = [int(row["performance"]["peak_vram_bytes"]) for row in rows if row["performance"]["peak_vram_bytes"] is not None]
    return {
        "input_tokens_median": round(statistics.median(inputs), 3) if inputs else None,
        "input_tokens_p95": _percentile([float(value) for value in inputs], 0.95),
        "input_tokens_max": max(inputs, default=None),
        "first_token_latency_ms_median": round(statistics.median(first), 3) if first else None,
        "first_token_latency_ms_p95": _percentile(first, 0.95),
        "total_latency_ms_median": round(statistics.median(total), 3) if total else None,
        "total_latency_ms_p95": _percentile(total, 0.95),
        "tokens_per_second_median": round(statistics.median(speed), 3) if speed else None,
        "peak_vram_bytes": max(peaks, default=None),
    }


def _subset_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "schema": aggregate_schema_metrics(rows),
        "persona": aggregate_persona_metrics(rows),
        "memorization": aggregate_memorization_metrics(rows),
        "performance": _performance(rows),
    }


def run(args: argparse.Namespace) -> Path:
    if not args.acknowledge_locked_eval_is_evaluation_only:
        raise PipelineError("explicit locked-eval usage acknowledgement is required")
    if not RUN_ID.fullmatch(args.run_id):
        raise PipelineError("run ID must be a safe 3-80 character identifier")
    if args.progress_every <= 0:
        raise PipelineError("progress interval must be positive")
    run_commit = require_clean_git_worktree()
    output_dir = args.output_root.resolve() / args.run_id
    if output_dir.exists():
        raise PipelineError(f"refusing to overwrite locked-eval run: {output_dir}")

    cases, eval_hashes = load_locked_cases(args.eval_root)
    prompt, response_schema, contract_hashes = frozen_prompt_contract()
    allowed_tags = list(response_schema["properties"]["expression_tag"]["enum"])
    rag = FrozenRag(args.rag_jsonl)
    manifest_path = args.locked_manifest.resolve()
    manifest_sha256 = require_git_tracked_file(manifest_path)
    frozen = read_json(manifest_path)
    suite_id = frozen.get("suite_id")
    if not isinstance(suite_id, str) or not RUN_ID.fullmatch(suite_id):
        raise PipelineError("locked-eval manifest requires a safe suite ID")
    if frozen.get("counts") != {"jp": 92, "zh": 92, "total": 184}:
        raise PipelineError("locked-eval manifest must freeze exactly 92 JP and 92 ZH cases")
    if eval_hashes != frozen.get("input_hashes"):
        raise PipelineError("locked eval hashes differ from the committed frozen manifest")
    if contract_hashes["prompt_sha256"] != frozen.get("frozen_prompt_sha256"):
        raise PipelineError("runtime prompt differs from the committed locked-eval prompt")
    if contract_hashes["response_schema_sha256"] != frozen.get("response_schema_sha256"):
        raise PipelineError("response schema differs from the committed locked-eval contract")
    if args.run_kind == "l0_prompt_rag" and args.rag_jsonl is None:
        raise PipelineError("L0 Prompt + RAG requires --rag-jsonl")
    if args.run_kind == "l0_base" and args.rag_jsonl is not None:
        raise PipelineError("L0 base must run without RAG; use l0_prompt_rag for the RAG baseline")
    if rag.source_hash is not None and rag.source_hash != frozen.get("rag_train_sha256"):
        raise PipelineError("RAG input differs from the committed frozen manifest")
    if args.train_jsonl:
        exact_index, train_candidates = load_training_reply_index(args.train_jsonl)
        train_hash = sha256_file(args.train_jsonl)
    else:
        exact_index, train_candidates, train_hash = set(), [], None

    if args.backend == "local":
        if args.config is None:
            raise PipelineError("--config is required for local evaluation")
        config = load_yaml(args.config)
        generation, generation_profile = resolve_generation_settings(
            args,
            training_config=config,
            adapter_path=args.adapter,
        )
        backend = LocalUnslothBackend(
            config,
            allow_download=args.allow_download,
            adapter_path=args.adapter,
            input_pad_length=args.input_pad_length,
            **generation,
        )
        config_hash = sha256_file(args.config)
    else:
        if any(
            value is not None
            for value in (
                args.generation_profile,
                args.max_new_tokens,
                args.repetition_penalty,
                args.no_repeat_ngram_size,
                args.force_json_object_start,
            )
        ):
            raise PipelineError("local generation controls cannot be used with endpoint evaluation")
        generation_profile = None
        required = (args.endpoint, args.model, args.model_revision, args.tokenizer_revision)
        if any(not value for value in required):
            raise PipelineError(
                "endpoint evaluation requires --endpoint, --model, --model-revision and --tokenizer-revision"
            )
        backend = OpenAIBackend(
            base_url=args.endpoint,
            model=args.model,
            model_revision=args.model_revision,
            tokenizer_revision=args.tokenizer_revision,
            response_schema=response_schema,
            api_key=os.environ.get(args.api_key_env),
            timeout_seconds=args.timeout_seconds,
        )
        config_hash = None

    output_dir.mkdir(parents=True, exist_ok=False)
    rows: list[dict[str, Any]] = []
    errors = 0
    raw_path = output_dir / "raw_outputs.jsonl"
    with raw_path.open("x", encoding="utf-8", newline="\n") as raw_handle:
        for index, case in enumerate(cases, 1):
            request = case_request(case, rag, allowed_tags)
            try:
                generated = backend.generate(prompt, canonical_json(request["context"]))
                raw_output = generated.raw_output
                performance = {
                    "input_tokens": generated.input_tokens,
                    "first_token_latency_ms": generated.first_token_latency_ms,
                    "total_latency_ms": generated.total_latency_ms,
                    "generated_tokens": generated.generated_tokens,
                    "tokens_per_second": generated.tokens_per_second,
                    "peak_vram_bytes": generated.peak_vram_bytes,
                }
                backend_error = None
            except PipelineError as exc:
                errors += 1
                raw_output = ""
                performance = {
                    "input_tokens": None,
                    "first_token_latency_ms": None,
                    "total_latency_ms": None,
                    "generated_tokens": None,
                    "tokens_per_second": None,
                    "peak_vram_bytes": None,
                }
                backend_error = str(exc)
            row = {
                "sequence": index,
                "sample_id": case["sample_id"],
                "case_fingerprint": request["case_fingerprint"],
                "expected": request["expected"],
                "raw_output": raw_output,
                "metrics": evaluate_output(raw_output, request["expected"]),
                "persona_metrics": evaluate_persona(raw_output, request["expected"]),
                "memorization_metrics": evaluate_memorization(raw_output, exact_index, train_candidates),
                "performance": performance,
                "backend_error": backend_error,
            }
            rows.append(row)
            raw_handle.write(canonical_json(row) + "\n")
            raw_handle.flush()
            if index % args.progress_every == 0 or index == len(cases):
                print(
                    json.dumps(
                        {
                            "event": "locked_eval.progress",
                            "run_id": args.run_id,
                            "completed": index,
                            "total": len(cases),
                            "backend_errors": errors,
                        },
                        ensure_ascii=False,
                    ),
                    flush=True,
                )
    by_language = {
        language: _subset_metrics([row for row in rows if row["expected"]["language"] == language])
        for language in ("jp", "zh")
    }
    if require_clean_git_worktree() != run_commit:
        raise PipelineError("Git commit changed while locked evaluation was running")
    report = {
        "schema_version": 1,
        "run_id": args.run_id,
        "run_kind": args.run_kind,
        "status": "pass" if errors == 0 and len(rows) == 184 else "fail",
        "locked_eval_policy": {
            "evaluation_only": True,
            "used_for_training": False,
            "used_for_prompt_tuning": False,
            "used_for_early_stopping": False,
            "used_for_checkpoint_selection": False,
        },
        "counts": {"total": len(rows), **dict(Counter(row["expected"]["language"] for row in rows))},
        "backend_errors": errors,
        "model": backend.metadata,
        "metrics": _subset_metrics(rows),
        "metrics_by_language": by_language,
        "human_review": {
            "required_for_staging": True,
            "rubric_status": "not_scored_by_this_automatic_run",
            "fields": ["persona", "zh_naturalness", "jp_naturalness", "relationship_tone", "safety"],
        },
        "provenance": {
            "eval_input_hashes": eval_hashes,
            "locked_eval_suite_id": suite_id,
            "locked_eval_manifest_sha256": manifest_sha256,
            "rag_sha256": rag.source_hash,
            "train_jsonl_sha256_for_memorization_only": train_hash,
            "training_config_sha256": config_hash,
            "generation_profile_id": (
                generation_profile.profile_id if generation_profile is not None else None
            ),
            "generation_profile_sha256": (
                generation_profile.sha256 if generation_profile is not None else None
            ),
            **contract_hashes,
            "raw_outputs_sha256": sha256_file(raw_path),
            "code_commit": run_commit,
            "generated_at": utc_now(),
            "framework_versions": package_versions(
                ["torch", "transformers", "unsloth", "peft", "httpx", "pydantic"]
            ),
        },
    }
    write_json(output_dir / "report.json", report)
    return output_dir


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description="Run the frozen 92+92 Meguri locked evaluation")
    value.add_argument("--run-id", required=True)
    value.add_argument("--run-kind", choices=["l0_base", "l0_prompt_rag", "post_train"], required=True)
    value.add_argument("--eval-root", type=Path, required=True)
    value.add_argument(
        "--locked-manifest",
        type=Path,
        default=Path(__file__).parent / "fixtures" / "locked_eval_manifest.json",
    )
    value.add_argument("--rag-jsonl", type=Path)
    value.add_argument("--train-jsonl", type=Path, help="Used only for output memorization comparison")
    value.add_argument("--backend", choices=["local", "openai"], required=True)
    value.add_argument("--config", type=Path)
    value.add_argument("--adapter", type=Path)
    value.add_argument("--generation-profile", type=Path)
    value.add_argument("--allow-download", action="store_true")
    value.add_argument("--max-new-tokens", type=int)
    value.add_argument("--input-pad-length", type=int)
    value.add_argument("--repetition-penalty", type=float)
    value.add_argument("--no-repeat-ngram-size", type=int)
    value.add_argument("--force-json-object-start", action="store_true", default=None)
    value.add_argument("--endpoint")
    value.add_argument("--model")
    value.add_argument("--model-revision")
    value.add_argument("--tokenizer-revision")
    value.add_argument("--api-key-env", default="MEGURI_LLM_API_KEY")
    value.add_argument("--timeout-seconds", type=float, default=60.0)
    value.add_argument("--progress-every", type=int, default=10)
    value.add_argument("--output-root", type=Path, default=ARTIFACT_ROOT / "eval")
    value.add_argument("--acknowledge-locked-eval-is-evaluation-only", action="store_true")
    return value


def main() -> int:
    try:
        output = run(parser().parse_args())
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "output_dir": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
