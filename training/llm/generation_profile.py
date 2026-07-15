from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from training.llm.eval.backends import validate_generation_controls
from training.llm.scripts.common import PipelineError, load_yaml, read_json, sha256_file
from training.llm.scripts.export_adapter import adapter_hash


PROFILE_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REVISION = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class GenerationProfile:
    path: Path
    profile_id: str
    sha256: str
    model_repo_id: str
    model_revision: str
    tokenizer_revision: str
    adapter_model_id: str
    adapter_sha256: str
    max_new_tokens: int
    repetition_penalty: float
    no_repeat_ngram_size: int
    force_json_object_start: bool
    previous_locked_eval_suite_id: str
    previous_locked_eval_input_hashes_sha256: str

    def backend_kwargs(self) -> dict[str, Any]:
        return {
            "max_new_tokens": self.max_new_tokens,
            "repetition_penalty": self.repetition_penalty,
            "no_repeat_ngram_size": self.no_repeat_ngram_size,
            "force_json_object_start": self.force_json_object_start,
        }

    def report_identity(self) -> dict[str, str]:
        return {
            "generation_profile_id": self.profile_id,
            "generation_profile_sha256": self.sha256,
        }


def _require_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise PipelineError(f"generation profile {label} fields differ from the pinned schema")
    return value


def _rate(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 1:
        raise PipelineError(f"generation profile {label} must be a rate between 0 and 1")
    return float(value)


def _validate_schema(value: dict[str, Any]) -> None:
    _require_keys(
        value,
        {
            "schema_version",
            "profile_id",
            "status",
            "model",
            "adapter",
            "generation",
            "validation_evidence",
            "safety_evidence",
            "policy",
        },
        "root",
    )
    if value["schema_version"] != 1 or value["status"] != "validation_selected":
        raise PipelineError("generation profile schema version or status is not supported")
    if not isinstance(value["profile_id"], str) or not PROFILE_ID.fullmatch(value["profile_id"]):
        raise PipelineError("generation profile ID must be a safe 3-128 character identifier")
    model = _require_keys(value["model"], {"repo_id", "revision", "tokenizer_revision"}, "model")
    if not isinstance(model["repo_id"], str) or not model["repo_id"]:
        raise PipelineError("generation profile model repo is required")
    if not isinstance(model["revision"], str) or not REVISION.fullmatch(model["revision"]):
        raise PipelineError("generation profile model revision must be a 40-character digest")
    if not isinstance(model["tokenizer_revision"], str) or not REVISION.fullmatch(
        model["tokenizer_revision"]
    ):
        raise PipelineError("generation profile tokenizer revision must be a 40-character digest")
    adapter = _require_keys(value["adapter"], {"model_id", "sha256"}, "adapter")
    if not isinstance(adapter["model_id"], str) or not PROFILE_ID.fullmatch(adapter["model_id"]):
        raise PipelineError("generation profile adapter model ID is invalid")
    if not isinstance(adapter["sha256"], str) or not SHA256.fullmatch(adapter["sha256"]):
        raise PipelineError("generation profile adapter digest is invalid")
    generation = _require_keys(
        value["generation"],
        {"max_new_tokens", "repetition_penalty", "no_repeat_ngram_size", "force_json_object_start"},
        "generation",
    )
    if (
        isinstance(generation["max_new_tokens"], bool)
        or not isinstance(generation["max_new_tokens"], int)
        or not 1 <= generation["max_new_tokens"] <= 1024
    ):
        raise PipelineError("generation profile max new tokens must be between 1 and 1024")
    if not isinstance(generation["force_json_object_start"], bool):
        raise PipelineError("generation profile JSON object constraint must be boolean")
    if isinstance(generation["repetition_penalty"], bool) or not isinstance(
        generation["repetition_penalty"], (int, float)
    ):
        raise PipelineError("generation profile repetition penalty must be numeric")
    if isinstance(generation["no_repeat_ngram_size"], bool) or not isinstance(
        generation["no_repeat_ngram_size"], int
    ):
        raise PipelineError("generation profile no-repeat ngram size must be an integer")
    validation = _require_keys(
        value["validation_evidence"],
        {
            "run_id",
            "report_sha256",
            "validation_count",
            "composite_score",
            "json_parse_rate",
            "response_schema_valid_rate",
            "extra_field_rate",
            "locked_eval_accessed",
        },
        "validation evidence",
    )
    if not isinstance(validation["run_id"], str) or len(validation["run_id"]) < 3:
        raise PipelineError("generation profile validation run ID is invalid")
    if not isinstance(validation["report_sha256"], str) or not SHA256.fullmatch(
        validation["report_sha256"]
    ):
        raise PipelineError("generation profile validation report digest is invalid")
    if (
        isinstance(validation["validation_count"], bool)
        or not isinstance(validation["validation_count"], int)
        or validation["validation_count"] <= 0
    ):
        raise PipelineError("generation profile validation count must be positive")
    for field in ("composite_score", "json_parse_rate", "response_schema_valid_rate", "extra_field_rate"):
        _rate(validation[field], field)
    if validation["locked_eval_accessed"] is not False:
        raise PipelineError("generation profile selection must not access locked eval")
    safety = _require_keys(
        value["safety_evidence"],
        {"run_id", "report_sha256", "passed", "total"},
        "safety evidence",
    )
    if not isinstance(safety["run_id"], str) or len(safety["run_id"]) < 3:
        raise PipelineError("generation profile safety run ID is invalid")
    if not isinstance(safety["report_sha256"], str) or not SHA256.fullmatch(safety["report_sha256"]):
        raise PipelineError("generation profile safety report digest is invalid")
    if any(
        isinstance(safety[field], bool)
        or not isinstance(safety[field], int)
        or safety[field] <= 0
        for field in ("passed", "total")
    ):
        raise PipelineError("generation profile safety counts must be integers")
    policy = _require_keys(
        value["policy"],
        {
            "selection_scope",
            "staging_eligible",
            "production_ready",
            "requires_new_independently_frozen_locked_eval",
            "requires_frozen_rubric_human_review",
            "previous_locked_eval_suite_id",
            "previous_locked_eval_input_hashes_sha256",
            "previous_locked_eval_must_not_be_reused_for_tuning",
        },
        "policy",
    )
    expected_policy = {
        "selection_scope": "validation_and_frozen_synthetic_safety_only",
        "staging_eligible": False,
        "production_ready": False,
        "requires_new_independently_frozen_locked_eval": True,
        "requires_frozen_rubric_human_review": True,
        "previous_locked_eval_suite_id": policy["previous_locked_eval_suite_id"],
        "previous_locked_eval_input_hashes_sha256": policy[
            "previous_locked_eval_input_hashes_sha256"
        ],
        "previous_locked_eval_must_not_be_reused_for_tuning": True,
    }
    if (
        not isinstance(policy["previous_locked_eval_suite_id"], str)
        or not PROFILE_ID.fullmatch(policy["previous_locked_eval_suite_id"])
        or not isinstance(policy["previous_locked_eval_input_hashes_sha256"], str)
        or not SHA256.fullmatch(policy["previous_locked_eval_input_hashes_sha256"])
        or policy != expected_policy
    ):
        raise PipelineError("generation profile policy differs from the pinned validation-only contract")


def load_generation_profile(
    path: Path,
    *,
    training_config: dict[str, Any] | None = None,
    adapter_path: Path | None = None,
) -> GenerationProfile:
    resolved = path.resolve()
    value = load_yaml(resolved)
    _validate_schema(value)
    if value["safety_evidence"]["passed"] != value["safety_evidence"]["total"]:
        raise PipelineError("generation profile requires complete passing safety evidence")
    repetition_penalty, no_repeat_ngram_size = validate_generation_controls(
        value["generation"]["repetition_penalty"],
        value["generation"]["no_repeat_ngram_size"],
    )
    profile = GenerationProfile(
        path=resolved,
        profile_id=value["profile_id"],
        sha256=sha256_file(resolved),
        model_repo_id=value["model"]["repo_id"],
        model_revision=value["model"]["revision"],
        tokenizer_revision=value["model"]["tokenizer_revision"],
        adapter_model_id=value["adapter"]["model_id"],
        adapter_sha256=value["adapter"]["sha256"],
        max_new_tokens=int(value["generation"]["max_new_tokens"]),
        repetition_penalty=repetition_penalty,
        no_repeat_ngram_size=no_repeat_ngram_size,
        force_json_object_start=bool(value["generation"]["force_json_object_start"]),
        previous_locked_eval_suite_id=value["policy"]["previous_locked_eval_suite_id"],
        previous_locked_eval_input_hashes_sha256=value["policy"][
            "previous_locked_eval_input_hashes_sha256"
        ],
    )
    if training_config is not None:
        model = training_config.get("model", {})
        if model.get("repo_id") != profile.model_repo_id:
            raise PipelineError("generation profile model repo differs from the training config")
        if model.get("revision") != profile.model_revision:
            raise PipelineError("generation profile model revision differs from the training config")
        if model.get("tokenizer_revision") != profile.tokenizer_revision:
            raise PipelineError("generation profile tokenizer revision differs from the training config")
    if adapter_path is not None:
        digest, _ = adapter_hash(adapter_path)
        if digest != profile.adapter_sha256:
            raise PipelineError("generation profile adapter digest differs from the selected adapter")
        export_manifest = adapter_path / "export_manifest.json"
        if export_manifest.exists():
            manifest = read_json(export_manifest)
            if manifest.get("model_id") != profile.adapter_model_id:
                raise PipelineError("generation profile adapter model ID differs from the export")
    return profile


def resolve_generation_settings(
    args: Any,
    *,
    training_config: dict[str, Any],
    adapter_path: Path | None,
    default_max_new_tokens: int = 256,
) -> tuple[dict[str, Any], GenerationProfile | None]:
    profile_path = getattr(args, "generation_profile", None)
    explicit = {
        "max_new_tokens": getattr(args, "max_new_tokens", None),
        "repetition_penalty": getattr(args, "repetition_penalty", None),
        "no_repeat_ngram_size": getattr(args, "no_repeat_ngram_size", None),
        "force_json_object_start": getattr(args, "force_json_object_start", None),
    }
    if profile_path is not None:
        if any(value is not None for value in explicit.values()):
            raise PipelineError("generation profile cannot be combined with generation overrides")
        if adapter_path is None:
            raise PipelineError("generation profile requires an explicit adapter")
        profile = load_generation_profile(
            profile_path,
            training_config=training_config,
            adapter_path=adapter_path,
        )
        return profile.backend_kwargs(), profile
    max_new_tokens = explicit["max_new_tokens"]
    if max_new_tokens is None:
        max_new_tokens = default_max_new_tokens
    if (
        isinstance(max_new_tokens, bool)
        or not isinstance(max_new_tokens, int)
        or not 1 <= max_new_tokens <= 1024
    ):
        raise PipelineError("max new tokens must be between 1 and 1024")
    repetition_penalty = 1.0 if explicit["repetition_penalty"] is None else explicit["repetition_penalty"]
    no_repeat_ngram_size = (
        0 if explicit["no_repeat_ngram_size"] is None else explicit["no_repeat_ngram_size"]
    )
    repetition_penalty, no_repeat_ngram_size = validate_generation_controls(
        repetition_penalty,
        no_repeat_ngram_size,
    )
    return (
        {
            "max_new_tokens": max_new_tokens,
            "repetition_penalty": repetition_penalty,
            "no_repeat_ngram_size": no_repeat_ngram_size,
            "force_json_object_start": bool(explicit["force_json_object_start"]),
        },
        None,
    )
