from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from typing import Any

from training.llm.generation_profile import load_generation_profile
from training.llm.scripts.common import (
    LLM_ROOT,
    PipelineError,
    canonical_json,
    load_yaml,
    read_json,
    sha256_text,
    utc_now,
)
from training.llm.scripts.export_adapter import adapter_hash


ALLOWED_REGISTER_STATUS = {"experimental", "evaluated", "staging_candidate"}
MODEL_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}$")


def _validate_registry(registry: dict[str, Any], schema_path: Path) -> None:
    try:
        import jsonschema
    except ImportError as exc:
        raise PipelineError("jsonschema is required for model registration") from exc
    try:
        jsonschema.validate(registry, read_json(schema_path))
    except jsonschema.ValidationError as exc:
        raise PipelineError(f"model registry schema validation failed: {exc.message}") from exc


def _atomic_write(path: Path, value: dict[str, Any]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    if temporary.exists():
        raise PipelineError(f"stale registry temporary file exists: {temporary}")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    os.replace(temporary, path)


def register(
    *,
    registry_path: Path,
    schema_path: Path,
    export_dir: Path,
    experiment_path: Path,
    selection_path: Path,
    locked_eval_path: Path,
    comparison_path: Path,
    status: str,
    parent_model_id: str | None,
    rollback_model_id: str | None,
    generation_profile_path: Path | None = None,
    model_id: str | None = None,
) -> dict[str, Any]:
    if status not in ALLOWED_REGISTER_STATUS:
        raise PipelineError("training registration cannot mark staging/production active")
    registry = read_json(registry_path)
    _validate_registry(registry, schema_path)
    export = read_json(export_dir / "export_manifest.json")
    experiment = read_json(experiment_path)
    selection = read_json(selection_path)
    locked = read_json(locked_eval_path)
    comparison = read_json(comparison_path)
    digest, _ = adapter_hash(export_dir)
    if digest != export.get("adapter_sha256"):
        raise PipelineError("exported adapter content no longer matches its manifest")
    if experiment.get("status") != "pass" or experiment.get("experiment_id") != export.get("experiment_id"):
        raise PipelineError("experiment/export identity mismatch")
    if selection.get("selected", {}).get("adapter_sha256") != digest:
        raise PipelineError("selected validation checkpoint does not match exported adapter")
    if locked.get("status") != "pass" or locked.get("counts", {}).get("total") != 184:
        raise PipelineError("complete passing locked evaluation is required for registration")
    if locked.get("model", {}).get("adapter_path"):
        evaluated_digest, _ = adapter_hash(Path(locked["model"]["adapter_path"]))
        if evaluated_digest != digest:
            raise PipelineError("locked eval adapter does not match exported adapter")
    if comparison.get("candidate", {}).get("run_id") != locked.get("run_id"):
        raise PipelineError("comparison candidate does not match locked-eval run")
    generation_profile = None
    if generation_profile_path is not None:
        generation_profile = load_generation_profile(
            generation_profile_path,
            training_config=load_yaml(Path(experiment["training_config"])),
            adapter_path=export_dir,
        )
        locked_provenance = locked.get("provenance", {})
        comparison_provenance = comparison.get("provenance", {})
        for label, provenance in (
            ("locked eval", locked_provenance),
            ("comparison", comparison_provenance),
        ):
            if provenance.get("generation_profile_id") != generation_profile.profile_id:
                raise PipelineError(f"{label} generation profile ID mismatch")
            if provenance.get("generation_profile_sha256") != generation_profile.sha256:
                raise PipelineError(f"{label} generation profile digest mismatch")
        locked_suite_id = locked_provenance.get("locked_eval_suite_id")
        if not locked_suite_id:
            raise PipelineError("profile-bound registration requires a versioned locked-eval suite")
        if locked_suite_id == generation_profile.previous_locked_eval_suite_id:
            raise PipelineError("generation profile must use a new independently frozen locked-eval suite")
        locked_input_hashes = locked_provenance.get("eval_input_hashes")
        if not isinstance(locked_input_hashes, dict):
            raise PipelineError("profile-bound registration requires frozen locked-eval input hashes")
        if sha256_text(canonical_json(locked_input_hashes)) == (
            generation_profile.previous_locked_eval_input_hashes_sha256
        ):
            raise PipelineError("generation profile must not reuse the previous locked-eval inputs")
        locked_manifest_sha256 = locked_provenance.get("locked_eval_manifest_sha256")
        if not locked_manifest_sha256:
            raise PipelineError("profile-bound registration requires a locked-eval manifest digest")
        locked_source_build_id = locked_provenance.get("locked_eval_source_build_id")
        if not locked_source_build_id:
            raise PipelineError("profile-bound registration requires an evaluation source build")
        if comparison_provenance.get("locked_eval_suite_id") != locked_suite_id:
            raise PipelineError("comparison locked-eval suite identity mismatch")
        if comparison_provenance.get("locked_eval_manifest_sha256") != locked_manifest_sha256:
            raise PipelineError("comparison locked-eval manifest identity mismatch")
        if comparison_provenance.get("locked_eval_source_build_id") != locked_source_build_id:
            raise PipelineError("comparison evaluation source build identity mismatch")
        independent_validation = locked.get("independent_suite_validation")
        if not isinstance(independent_validation, dict) or independent_validation.get("status") != "pass":
            raise PipelineError("profile-bound registration requires passing suite independence validation")
        independent_validation_sha256 = sha256_text(canonical_json(independent_validation))
        if (
            comparison_provenance.get("independent_suite_validation_sha256")
            != independent_validation_sha256
        ):
            raise PipelineError("comparison suite-independence evidence mismatch")
    elif locked.get("provenance", {}).get("generation_profile_sha256") is not None:
        raise PipelineError("registration must bind the generation profile used by locked eval")
    if status == "staging_candidate":
        if comparison.get("staging_gate", {}).get("status") != "pass":
            raise PipelineError("staging_candidate requires a passing comparison gate")
        if not rollback_model_id:
            raise PipelineError("staging_candidate requires an explicit rollback_model_id")
        if generation_profile is None:
            raise PipelineError("staging_candidate requires a pinned generation profile")
    resolved_model_id = str(model_id or export["model_id"])
    if not MODEL_ID.fullmatch(resolved_model_id):
        raise PipelineError("model ID must be a safe 3-128 character identifier")
    if model_id is not None and generation_profile is None:
        raise PipelineError("a deployment model ID override requires a pinned generation profile")
    if any(item.get("model_id") == resolved_model_id for item in registry["models"]):
        raise PipelineError(f"model is already registered: {resolved_model_id}")
    entry = {
        "model_id": resolved_model_id,
        "status": status,
        "base_model": experiment["base_model_repo"],
        "base_revision": experiment["base_model_revision"],
        "tokenizer_revision": experiment["tokenizer_revision"],
        "adapter_revision": digest[:16],
        "adapter_sha256": digest,
        "artifact_path": str(export_dir.resolve()),
        "dataset_id": experiment["dataset_id"],
        "data_build_id": experiment["data_build_id"],
        "prompt_sha256": experiment["prompt_sha256"],
        "response_schema_sha256": experiment["response_schema_sha256"],
        "chat_template_sha256": experiment["chat_template_sha256"],
        "training_commit": experiment["training_commit"],
        "framework_versions": experiment["framework_versions"],
        "training_config": str(Path(experiment["training_config"]).resolve()),
        "experiment_manifest": str(experiment_path.resolve()),
        "validation_selection": str(selection_path.resolve()),
        "locked_eval_report": str(locked_eval_path.resolve()),
        "comparison_report": str(comparison_path.resolve()),
        "generation_profile": (
            str(generation_profile.path) if generation_profile is not None else None
        ),
        "generation_profile_id": (
            generation_profile.profile_id if generation_profile is not None else None
        ),
        "generation_profile_sha256": (
            generation_profile.sha256 if generation_profile is not None else None
        ),
        "locked_eval_suite_id": (
            locked.get("provenance", {}).get("locked_eval_suite_id")
            if generation_profile is not None
            else None
        ),
        "locked_eval_source_build_id": (
            locked.get("provenance", {}).get("locked_eval_source_build_id")
            if generation_profile is not None
            else None
        ),
        "locked_eval_manifest_sha256": (
            locked.get("provenance", {}).get("locked_eval_manifest_sha256")
            if generation_profile is not None
            else None
        ),
        "independent_suite_validation_sha256": (
            sha256_text(canonical_json(locked.get("independent_suite_validation")))
            if generation_profile is not None
            else None
        ),
        "created_at": utc_now(),
        "parent_model_id": parent_model_id,
        "rollback_model_id": rollback_model_id,
    }
    registry["models"].append(entry)
    registry["updated_at"] = utc_now()
    _validate_registry(registry, schema_path)
    _atomic_write(registry_path, registry)
    return entry


def main() -> int:
    parser = argparse.ArgumentParser(description="Register an evaluated Meguri adapter")
    parser.add_argument("--registry", type=Path, default=LLM_ROOT / "registry" / "model_registry.json")
    parser.add_argument("--schema", type=Path, default=LLM_ROOT / "registry" / "model_registry.schema.json")
    parser.add_argument("--export-dir", type=Path, required=True)
    parser.add_argument("--experiment-manifest", type=Path, required=True)
    parser.add_argument("--validation-selection", type=Path, required=True)
    parser.add_argument("--locked-eval-report", type=Path, required=True)
    parser.add_argument("--comparison-report", type=Path, required=True)
    parser.add_argument("--generation-profile", type=Path)
    parser.add_argument("--model-id")
    parser.add_argument("--status", choices=sorted(ALLOWED_REGISTER_STATUS), default="evaluated")
    parser.add_argument("--parent-model-id")
    parser.add_argument("--rollback-model-id")
    args = parser.parse_args()
    try:
        entry = register(
            registry_path=args.registry,
            schema_path=args.schema,
            export_dir=args.export_dir,
            experiment_path=args.experiment_manifest,
            selection_path=args.validation_selection,
            locked_eval_path=args.locked_eval_report,
            comparison_path=args.comparison_report,
            status=args.status,
            parent_model_id=args.parent_model_id,
            rollback_model_id=args.rollback_model_id,
            generation_profile_path=args.generation_profile,
            model_id=args.model_id,
        )
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "model": entry}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
