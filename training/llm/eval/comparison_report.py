from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from training.llm.scripts.common import (
    PipelineError,
    canonical_json,
    read_json,
    require_clean_git_worktree,
    sha256_file,
    utc_now,
    write_json,
)
from training.llm.scripts.export_adapter import adapter_hash


def automatic_quality_score(report: dict[str, Any]) -> float:
    metrics = report["metrics"]
    schema = metrics["schema"]
    persona = metrics["persona"]
    components = {
        "schema": schema["response_schema_valid_rate"],
        "language": schema["language_match_rate"],
        "identity": persona["identity_stable_heuristic_rate"],
        "relationship": persona["relationship_severe_error_free_heuristic_rate"],
        "mode": persona["interaction_mode_consistent_heuristic_rate"],
        "memory": 1.0 - schema["memory_candidate_error_rate"],
        "expression": (
            schema["expression_tag_accuracy"]
            + schema["expression_intensity_accuracy"]
            + schema["voice_style_accuracy"]
        )
        / 3,
        "memorization": 1.0 - metrics["memorization"]["high_similarity_match_rate"],
    }
    weights = {
        "schema": 0.25,
        "language": 0.15,
        "identity": 0.10,
        "relationship": 0.10,
        "mode": 0.10,
        "memory": 0.10,
        "expression": 0.10,
        "memorization": 0.10,
    }
    return round(sum(components[name] * weights[name] for name in weights), 6)


def staging_gate(
    candidate: dict[str, Any],
    *,
    baseline_score: float,
    safety: dict[str, Any],
    human: dict[str, Any] | None,
) -> dict[str, Any]:
    schema = candidate["metrics"]["schema"]
    persona = candidate["metrics"]["persona"]
    candidate_score = automatic_quality_score(candidate)
    improvement = (candidate_score - baseline_score) / baseline_score if baseline_score else 0.0
    checks = {
        "json_parse_rate_gte_0_995": schema["json_parse_rate"] >= 0.995,
        "schema_valid_rate_gte_0_99": schema["response_schema_valid_rate"] >= 0.99,
        "invalid_enum_rate_zero": schema["invalid_enum_rate"] == 0,
        "extra_field_rate_lte_0_005": schema["extra_field_rate"] <= 0.005,
        "relationship_severe_error_rate_lte_0_05": (
            1.0 - persona["relationship_severe_error_free_heuristic_rate"] <= 0.05
        ),
        "mode_severe_error_rate_lte_0_05": (
            1.0 - persona["interaction_mode_consistent_heuristic_rate"] <= 0.05
        ),
        "memory_candidate_error_rate_lte_0_03": schema["memory_candidate_error_rate"] <= 0.03,
        "safety_no_severe_regression": safety.get("status") == "pass",
        "improvement_gte_5_percent": improvement >= 0.05,
        "human_persona_score_gte_0_90": bool(
            human and human.get("approved") is True and float(human.get("persona_score", 0)) >= 0.90
        ),
    }
    return {
        "status": "pass" if all(checks.values()) else "fail",
        "checks": checks,
        "candidate_automatic_score": candidate_score,
        "baseline_automatic_score": baseline_score,
        "relative_improvement": round(improvement, 6),
        "production_ready": False,
        "note": "Passing this gate permits staging_candidate only; production requires separate approval.",
    }


def compare(
    baseline_paths: list[Path],
    candidate_path: Path,
    safety_path: Path,
    human_path: Path | None,
    output: Path,
    *,
    comparison_commit: str | None = None,
) -> dict[str, Any]:
    if output.exists():
        raise PipelineError(f"refusing to overwrite comparison report: {output}")
    baselines = [read_json(path) for path in baseline_paths]
    candidate = read_json(candidate_path)
    safety = read_json(safety_path)
    human = read_json(human_path) if human_path else None
    reports = [*baselines, candidate]
    if any(report.get("status") != "pass" for report in reports):
        raise PipelineError("all comparison locked-eval reports must pass")
    if any(report.get("counts", {}).get("total") != 184 for report in reports):
        raise PipelineError("all comparison reports must cover the complete 184-case locked eval")
    prompt_hashes = {report.get("provenance", {}).get("prompt_sha256") for report in reports}
    schema_hashes = {
        report.get("provenance", {}).get("response_schema_sha256") for report in reports
    }
    pad_lengths = {report.get("model", {}).get("input_pad_length") for report in reports}
    if len(prompt_hashes) != 1 or None in prompt_hashes:
        raise PipelineError("comparison reports must use one pinned Prompt")
    if len(schema_hashes) != 1 or None in schema_hashes:
        raise PipelineError("comparison reports must use one pinned response schema")
    if len(pad_lengths) != 1 or None in pad_lengths:
        raise PipelineError("comparison reports must use one fixed input padding length")
    eval_inputs = {
        canonical_json(report.get("provenance", {}).get("eval_input_hashes")) for report in reports
    }
    if len(eval_inputs) != 1 or "null" in eval_inputs:
        raise PipelineError("comparison reports must use the same frozen evaluation inputs")
    suite_ids = {
        report.get("provenance", {}).get("locked_eval_suite_id") for report in reports
    }
    manifest_hashes = {
        report.get("provenance", {}).get("locked_eval_manifest_sha256") for report in reports
    }
    if candidate.get("provenance", {}).get("generation_profile_sha256") is not None:
        if len(suite_ids) != 1 or None in suite_ids:
            raise PipelineError("profile-bound comparison requires one locked-eval suite ID")
        if len(manifest_hashes) != 1 or None in manifest_hashes:
            raise PipelineError("profile-bound comparison requires one locked-eval manifest")
    if candidate.get("locked_eval_policy", {}).get("used_for_checkpoint_selection") is not False:
        raise PipelineError("candidate locked eval must not influence checkpoint selection")
    if safety.get("status") != "pass" or safety.get("passed") != safety.get("total"):
        raise PipelineError("complete passing safety evaluation is required")
    candidate_adapter = Path(str(candidate.get("model", {}).get("adapter_path") or ""))
    safety_adapter = Path(str(safety.get("model", {}).get("adapter_path") or ""))
    if not candidate_adapter.is_dir() or not safety_adapter.is_dir():
        raise PipelineError("candidate and safety reports must identify existing adapters")
    candidate_digest, _ = adapter_hash(candidate_adapter)
    safety_digest, _ = adapter_hash(safety_adapter)
    if candidate_digest != safety_digest:
        raise PipelineError("candidate locked eval and safety report use different adapters")
    candidate_profile_id = candidate.get("provenance", {}).get("generation_profile_id")
    candidate_profile_sha256 = candidate.get("provenance", {}).get("generation_profile_sha256")
    safety_profile_id = safety.get("provenance", {}).get("generation_profile_id")
    safety_profile_sha256 = safety.get("provenance", {}).get("generation_profile_sha256")
    if (candidate_profile_id, candidate_profile_sha256) != (
        safety_profile_id,
        safety_profile_sha256,
    ):
        raise PipelineError("candidate locked eval and safety report use different generation profiles")
    hashes = {
        "baseline_reports": [sha256_file(path) for path in baseline_paths],
        "candidate_report": sha256_file(candidate_path),
        "safety_report": sha256_file(safety_path),
        "human_report": sha256_file(human_path) if human_path else None,
    }
    if human is not None:
        evidence = human.get("evidence", {})
        if (
            human.get("status") != "complete"
            or human.get("counts", {}).get("total") != 184
            or human.get("counts", {}).get("rated") != 184
        ):
            raise PipelineError("human review must be a complete 184-item frozen-rubric result")
        if evidence.get("locked_eval_report_sha256") != hashes["candidate_report"]:
            raise PipelineError("human review was not produced for the candidate locked eval")
        if evidence.get("candidate_run_id") != candidate.get("run_id"):
            raise PipelineError("human review candidate run identity mismatch")
        if evidence.get("raw_outputs_sha256") != candidate.get("provenance", {}).get(
            "raw_outputs_sha256"
        ):
            raise PipelineError("human review raw-output identity mismatch")
        if (
            evidence.get("generation_profile_id"),
            evidence.get("generation_profile_sha256"),
        ) != (candidate_profile_id, candidate_profile_sha256):
            raise PipelineError("human review generation profile identity mismatch")
        if evidence.get("locked_eval_suite_id") != candidate.get("provenance", {}).get(
            "locked_eval_suite_id"
        ):
            raise PipelineError("human review locked-eval suite identity mismatch")
        if human.get("component_rates", {}).get("safety_acceptable") != 1.0:
            raise PipelineError("human review contains a safety rejection")
        language_naturalness = human.get("language_naturalness_by_language", {})
        if set(language_naturalness) != {"jp", "zh"} or any(
            not isinstance(rate, (int, float)) or isinstance(rate, bool) or not 0 <= rate <= 1
            for rate in language_naturalness.values()
        ):
            raise PipelineError("human review language-specific naturalness rates are invalid")
        component_rates = human.get("component_rates", {})
        persona_score = round(
            sum(
                float(component_rates.get(field, -1))
                for field in (
                    "persona_consistent",
                    "language_natural",
                    "relationship_tone_appropriate",
                )
            )
            / 3,
            6,
        )
        if persona_score != human.get("persona_score"):
            raise PipelineError("human review persona score is inconsistent with component rates")
        expected_approval = persona_score >= 0.90 and all(
            rate >= 0.90 for rate in language_naturalness.values()
        )
        if human.get("approved") != expected_approval:
            raise PipelineError("human review approval is inconsistent with the frozen rubric")
        reviewer = human.get("reviewer", {})
        if (
            reviewer.get("independent_of_training_and_prompt_tuning") is not True
            or reviewer.get("locked_eval_content_not_used_for_tuning") is not True
        ):
            raise PipelineError("human review declarations are incomplete")
    baseline_rows = [
        {"run_id": report["run_id"], "automatic_score": automatic_quality_score(report)}
        for report in baselines
    ]
    strongest = max(row["automatic_score"] for row in baseline_rows)
    result = {
        "schema_version": 1,
        "status": "complete",
        "baselines": baseline_rows,
        "candidate": {
            "run_id": candidate["run_id"],
            "automatic_score": automatic_quality_score(candidate),
        },
        "staging_gate": staging_gate(candidate, baseline_score=strongest, safety=safety, human=human),
        "provenance": {
            **hashes,
            "adapter_sha256": candidate_digest,
            "prompt_sha256": next(iter(prompt_hashes)),
            "response_schema_sha256": next(iter(schema_hashes)),
            "input_pad_length": next(iter(pad_lengths)),
            "generation_profile_id": candidate_profile_id,
            "generation_profile_sha256": candidate_profile_sha256,
            "locked_eval_suite_id": (
                next(iter(suite_ids)) if len(suite_ids) == 1 else None
            ),
            "locked_eval_manifest_sha256": (
                next(iter(manifest_hashes)) if len(manifest_hashes) == 1 else None
            ),
            "comparison_code_commit": comparison_commit,
            "created_at": utc_now(),
        },
    }
    if comparison_commit is not None and require_clean_git_worktree() != comparison_commit:
        raise PipelineError("Git commit changed while comparison was running")
    write_json(output, result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare a candidate with all frozen L0/provider baselines")
    parser.add_argument("--baseline", type=Path, action="append", required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--safety", type=Path, required=True)
    parser.add_argument("--human-review", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        comparison_commit = require_clean_git_worktree()
        result = compare(
            args.baseline,
            args.candidate,
            args.safety,
            args.human_review,
            args.output,
            comparison_commit=comparison_commit,
        )
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
