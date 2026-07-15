from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from training.llm.eval.backends import LocalUnslothBackend
from training.llm.eval.persona_eval import aggregate_persona_metrics, evaluate_persona
from training.llm.eval.schema_eval import aggregate_schema_metrics, evaluate_output
from training.llm.scripts.common import (
    ARTIFACT_ROOT,
    PipelineError,
    canonical_json,
    load_yaml,
    package_versions,
    read_json,
    read_jsonl,
    require_clean_git_worktree,
    sha256_file,
    utc_now,
    write_json,
)
from training.llm.scripts.export_adapter import adapter_hash
from training.llm.scripts.training_utils import EXPERIMENT_ID


def _expected(row: dict[str, Any]) -> dict[str, Any]:
    metadata = row["metadata"]
    payload = json.loads(row["messages"][-1]["content"])
    return {
        "language": "jp" if metadata["language"] == "ja" else "zh",
        "relationship_stage": metadata["relationship_stage"],
        "interaction_mode": metadata["interaction_mode"],
        "expression_tag": payload["expression_tag"],
        "expression_intensity": payload["expression_intensity"],
        "voice_style": payload["voice_style"],
    }


def _composite(schema: dict[str, Any], persona: dict[str, Any], safety_rate: float) -> dict[str, Any]:
    components = {
        "schema": schema["response_schema_valid_rate"],
        "language": schema["language_match_rate"],
        "relationship": persona["relationship_severe_error_free_heuristic_rate"],
        "mode": persona["interaction_mode_consistent_heuristic_rate"],
        "expression": schema["expression_tag_accuracy"],
        "intensity": schema["expression_intensity_accuracy"],
        "voice": schema["voice_style_accuracy"],
        "memory_boundary": 1.0 - schema["memory_candidate_error_rate"],
        "safety": safety_rate,
    }
    weights = {
        "schema": 0.25,
        "language": 0.15,
        "relationship": 0.15,
        "mode": 0.10,
        "expression": 0.05,
        "intensity": 0.05,
        "voice": 0.05,
        "memory_boundary": 0.10,
        "safety": 0.10,
    }
    return {
        "score": round(sum(components[name] * weights[name] for name in weights), 6),
        "components": components,
        "weights": weights,
        "selection_scope": "validation plus frozen synthetic safety only; locked eval is excluded",
        "human_persona_review_still_required": True,
    }


def run(args: argparse.Namespace) -> Path:
    if not EXPERIMENT_ID.fullmatch(args.run_id):
        raise PipelineError("validation run ID must be a safe identifier")
    if args.progress_every <= 0:
        raise PipelineError("progress interval must be positive")
    run_commit = require_clean_git_worktree()
    manifest = read_json(args.dataset_dir / "dataset_manifest.json")
    validation_path = args.dataset_dir / "validation.jsonl"
    if sha256_file(validation_path) != manifest.get("files", {}).get("validation.jsonl"):
        raise PipelineError("validation dataset hash mismatch")
    safety = read_json(args.safety_report)
    if safety.get("model", {}).get("adapter_path") != str(args.adapter.resolve()):
        raise PipelineError("safety report was not produced for the same adapter/checkpoint")
    config = load_yaml(args.config)
    backend = LocalUnslothBackend(
        config,
        allow_download=args.allow_download,
        adapter_path=args.adapter,
        max_new_tokens=256,
        input_pad_length=args.input_pad_length,
        repetition_penalty=args.repetition_penalty,
        no_repeat_ngram_size=args.no_repeat_ngram_size,
        force_json_object_start=args.force_json_object_start,
    )
    rows = [row for _, row in read_jsonl(validation_path)]
    output = args.output_root.resolve() / args.run_id
    if output.exists():
        raise PipelineError(f"refusing to overwrite validation evaluation: {output}")
    output.mkdir(parents=True, exist_ok=False)
    results: list[dict[str, Any]] = []
    raw_path = output / "raw_outputs.jsonl"
    with raw_path.open("x", encoding="utf-8", newline="\n") as raw_handle:
        for index, row in enumerate(rows, 1):
            messages = row["messages"]
            expected = _expected(row)
            generated = backend.generate(messages[0]["content"], messages[1]["content"])
            result = {
                "sample_id": row["metadata"]["sample_id"],
                "raw_output": generated.raw_output,
                "expected": expected,
                "metrics": evaluate_output(generated.raw_output, expected),
                "persona_metrics": evaluate_persona(generated.raw_output, expected),
            }
            results.append(result)
            raw_handle.write(canonical_json(result) + "\n")
            raw_handle.flush()
            if index % args.progress_every == 0 or index == len(rows):
                print(
                    json.dumps(
                        {
                            "event": "validation_eval.progress",
                            "run_id": args.run_id,
                            "completed": index,
                            "total": len(rows),
                        },
                        ensure_ascii=False,
                    ),
                    flush=True,
                )
    schema = aggregate_schema_metrics(results)
    persona = aggregate_persona_metrics(results)
    digest, _ = adapter_hash(args.adapter)
    if require_clean_git_worktree() != run_commit:
        raise PipelineError("Git commit changed while validation evaluation was running")
    report = {
        "schema_version": 1,
        "run_id": args.run_id,
        "status": "pass",
        "selection_eligible": safety.get("status") == "pass",
        "locked_eval_accessed": False,
        "model": backend.metadata,
        "adapter_sha256": digest,
        "dataset_id": manifest["dataset_id"],
        "validation_count": len(results),
        "schema_metrics": schema,
        "persona_metrics": persona,
        "safety_report_sha256": sha256_file(args.safety_report),
        "composite": _composite(schema, persona, float(safety["pass_rate"])),
        "provenance": {
            "validation_jsonl_sha256": sha256_file(validation_path),
            "raw_outputs_sha256": sha256_file(raw_path),
            "training_config_sha256": sha256_file(args.config),
            "code_commit": run_commit,
            "framework_versions": package_versions(
                ["torch", "transformers", "unsloth", "peft", "pydantic"]
            ),
            "generated_at": utc_now(),
        },
    }
    write_json(output / "report.json", report)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="Score a checkpoint on validation without locked-eval access")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--safety-report", type=Path, required=True)
    parser.add_argument("--allow-download", action="store_true")
    parser.add_argument("--input-pad-length", type=int)
    parser.add_argument("--repetition-penalty", type=float, default=1.0)
    parser.add_argument("--no-repeat-ngram-size", type=int, default=0)
    parser.add_argument("--force-json-object-start", action="store_true")
    parser.add_argument("--progress-every", type=int, default=10)
    parser.add_argument("--output-root", type=Path, default=ARTIFACT_ROOT / "validation_eval")
    args = parser.parse_args()
    try:
        output = run(args)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "output_dir": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
