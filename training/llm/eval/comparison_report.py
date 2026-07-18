from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from training.llm.scripts.common import PipelineError, read_json, sha256_file, utc_now, write_json


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
) -> dict[str, Any]:
    baselines = [read_json(path) for path in baseline_paths]
    candidate = read_json(candidate_path)
    safety = read_json(safety_path)
    human = read_json(human_path) if human_path else None
    if any(report.get("counts", {}).get("total") != 184 for report in [*baselines, candidate]):
        raise PipelineError("all comparison reports must cover the complete 184-case locked eval")
    hashes = {
        "baseline_reports": [sha256_file(path) for path in baseline_paths],
        "candidate_report": sha256_file(candidate_path),
        "safety_report": sha256_file(safety_path),
        "human_report": sha256_file(human_path) if human_path else None,
    }
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
        "provenance": {**hashes, "created_at": utc_now()},
    }
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
        result = compare(args.baseline, args.candidate, args.safety, args.human_review, args.output)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
