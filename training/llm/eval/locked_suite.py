from __future__ import annotations

import argparse
import difflib
import json
import re
from pathlib import Path
from typing import Any, Iterable

from training.llm.eval.eval_cases import (
    frozen_prompt_contract,
    load_locked_cases,
    locked_case_input_fingerprint,
    normalize_eval_input_text,
)
from training.llm.scripts.common import (
    PipelineError,
    canonical_json,
    read_json,
    read_jsonl,
    require_clean_git_worktree,
    require_git_tracked_file,
    sha256_file,
    sha256_text,
    utc_now,
    write_json,
)


SUITE_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{2,79}$")
POLICY = {
    "evaluation_only": True,
    "training": False,
    "prompt_tuning": False,
    "early_stopping": False,
    "checkpoint_selection": False,
}
NEAR_DUPLICATE_THRESHOLD = 0.95


def _set_digest(values: Iterable[str]) -> str:
    return sha256_text(canonical_json(sorted(set(values))))


def _last_user_message(messages: Any) -> str:
    if not isinstance(messages, list):
        raise PipelineError("suite identity row has no messages")
    users = [item for item in messages if isinstance(item, dict) and item.get("role") == "user"]
    if not users or not isinstance(users[-1].get("content"), str):
        raise PipelineError("suite identity row has no user message")
    value = users[-1]["content"].strip()
    if not value:
        raise PipelineError("suite identity user message is empty")
    return value


def _identity_summary(identity: dict[str, set[str]]) -> dict[str, Any]:
    return {
        "total": len(identity["sample_ids"]),
        "unique_sample_ids": len(identity["sample_ids"]),
        "unique_input_fingerprints": len(identity["input_fingerprints"]),
        "unique_normalized_input_texts": len(identity["input_texts"]),
        "unique_full_case_fingerprints": len(identity["full_case_fingerprints"]),
        "unique_scene_ids": len(identity["scene_ids"]),
        "sample_ids_sha256": _set_digest(identity["sample_ids"]),
        "input_fingerprints_sha256": _set_digest(identity["input_fingerprints"]),
        "normalized_input_texts_sha256": _set_digest(identity["input_texts"]),
        "full_case_fingerprints_sha256": _set_digest(identity["full_case_fingerprints"]),
        "scene_ids_sha256": _set_digest(identity["scene_ids"]),
    }


def locked_case_identity(cases: list[dict[str, Any]]) -> dict[str, set[str]]:
    identity = {
        "sample_ids": set(),
        "input_fingerprints": set(),
        "full_case_fingerprints": set(),
        "scene_ids": set(),
        "input_texts": set(),
    }
    for case in cases:
        metadata = case.get("metadata")
        if not isinstance(metadata, dict):
            raise PipelineError("locked suite case metadata is missing")
        sample_id = str(case.get("sample_id") or "")
        scene_id = str(metadata.get("scene_id") or "")
        if not sample_id or not scene_id:
            raise PipelineError("locked suite sample or scene identity is missing")
        identity["sample_ids"].add(sample_id)
        identity["scene_ids"].add(scene_id)
        identity["input_fingerprints"].add(locked_case_input_fingerprint(case))
        identity["full_case_fingerprints"].add(sha256_text(canonical_json(case)))
        identity["input_texts"].add(
            f"{case['language']}\0{normalize_eval_input_text(_last_user_message(case.get('messages')))}"
        )
    return identity


def derived_dataset_identity(paths: Iterable[Path]) -> dict[str, set[str]]:
    identity = {
        "sample_ids": set(),
        "input_fingerprints": set(),
        "full_case_fingerprints": set(),
        "scene_ids": set(),
        "input_texts": set(),
    }
    language_map = {"ja": "jp", "jp": "jp", "zh": "zh"}
    for path in paths:
        for _, row in read_jsonl(path):
            metadata = row.get("metadata")
            if not isinstance(metadata, dict):
                raise PipelineError("derived dataset row metadata is missing")
            language = language_map.get(str(metadata.get("language") or ""))
            sample_id = str(metadata.get("sample_id") or "")
            scene_id = str(metadata.get("scene_id") or "")
            relationship = metadata.get("relationship_stage")
            outfit = metadata.get("outfit_code")
            if not language or not sample_id or not scene_id or relationship in (None, "") or outfit in (None, ""):
                raise PipelineError("derived dataset identity fields are incomplete")
            projection = {
                "language": language,
                "user_message": normalize_eval_input_text(
                    _last_user_message(row.get("messages"))
                ),
                "relationship_stage": relationship,
                "outfit_code": outfit,
            }
            identity["sample_ids"].add(sample_id)
            identity["scene_ids"].add(scene_id)
            identity["input_fingerprints"].add(sha256_text(canonical_json(projection)))
            identity["input_texts"].add(f"{language}\0{projection['user_message']}")
    return identity


def _near_input_overlap(candidate: set[str], reference: set[str]) -> int:
    reference_by_language: dict[str, list[str]] = {"jp": [], "zh": []}
    for value in reference:
        language, text = value.split("\0", 1)
        reference_by_language.setdefault(language, []).append(text)
    overlaps = 0
    for value in candidate:
        language, text = value.split("\0", 1)
        if any(
            difflib.SequenceMatcher(a=text, b=other, autojunk=False).ratio()
            >= NEAR_DUPLICATE_THRESHOLD
            for other in reference_by_language.get(language, [])
        ):
            overlaps += 1
    return overlaps


def isolation_summary(
    candidate: dict[str, set[str]],
    train_validation: dict[str, set[str]],
    previous_locked: dict[str, set[str]],
) -> dict[str, int]:
    return {
        "candidate_duplicate_input_fingerprints": 184
        - len(candidate["input_fingerprints"]),
        "candidate_duplicate_normalized_input_texts": 184 - len(candidate["input_texts"]),
        "candidate_duplicate_full_case_fingerprints": 184
        - len(candidate["full_case_fingerprints"]),
        "train_validation_sample_id_overlap": len(
            candidate["sample_ids"] & train_validation["sample_ids"]
        ),
        "train_validation_input_fingerprint_overlap": len(
            candidate["input_fingerprints"] & train_validation["input_fingerprints"]
        ),
        "train_validation_scene_id_overlap": len(
            candidate["scene_ids"] & train_validation["scene_ids"]
        ),
        "train_validation_near_input_overlap": _near_input_overlap(
            candidate["input_texts"], train_validation["input_texts"]
        ),
        "previous_locked_sample_id_overlap": len(
            candidate["sample_ids"] & previous_locked["sample_ids"]
        ),
        "previous_locked_input_fingerprint_overlap": len(
            candidate["input_fingerprints"] & previous_locked["input_fingerprints"]
        ),
        "previous_locked_full_case_fingerprint_overlap": len(
            candidate["full_case_fingerprints"] & previous_locked["full_case_fingerprints"]
        ),
        "previous_locked_scene_id_overlap": len(
            candidate["scene_ids"] & previous_locked["scene_ids"]
        ),
        "previous_locked_near_input_overlap": _near_input_overlap(
            candidate["input_texts"], previous_locked["input_texts"]
        ),
    }


def _dataset_paths(dataset_dir: Path) -> tuple[dict[str, Any], Path, Path]:
    manifest_path = dataset_dir / "dataset_manifest.json"
    manifest = read_json(manifest_path)
    train_path = dataset_dir / "train.jsonl"
    validation_path = dataset_dir / "validation.jsonl"
    if sha256_file(train_path) != manifest.get("files", {}).get("train.jsonl"):
        raise PipelineError("derived train dataset hash mismatch")
    if sha256_file(validation_path) != manifest.get("files", {}).get("validation.jsonl"):
        raise PipelineError("derived validation dataset hash mismatch")
    return manifest, train_path, validation_path


def _load_previous(
    previous_manifest_path: Path,
    previous_eval_root: Path,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    previous = read_json(previous_manifest_path)
    cases, input_hashes = load_locked_cases(
        previous_eval_root,
        expected_source_build_id=str(previous.get("source_build_id") or ""),
    )
    if input_hashes != previous.get("input_hashes"):
        raise PipelineError("previous locked suite differs from its manifest")
    return previous, cases


def _validate_independent_declaration(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PipelineError("independent freeze declaration is missing")
    expected = {
        "prepared_by",
        "approved_by",
        "frozen_at",
        "source_authority",
        "training_team_had_content_access_before_freeze",
        "content_used_for_training_or_tuning",
        "generated_by_commit",
    }
    if set(value) != expected:
        raise PipelineError("independent freeze declaration fields differ from the v2 contract")
    prepared = str(value.get("prepared_by") or "").strip()
    approved = str(value.get("approved_by") or "").strip()
    if not prepared or not approved or prepared == approved:
        raise PipelineError("independent suite requires distinct preparer and approver identities")
    if not str(value.get("frozen_at") or "").strip() or not str(
        value.get("source_authority") or ""
    ).strip():
        raise PipelineError("independent suite freeze timestamp and source authority are required")
    if value.get("training_team_had_content_access_before_freeze") is not False:
        raise PipelineError("training-team pre-freeze content access must be declared false")
    if value.get("content_used_for_training_or_tuning") is not False:
        raise PipelineError("independent suite content must be declared measurement-only")
    commit = str(value.get("generated_by_commit") or "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise PipelineError("independent suite generator commit is invalid")
    return value


def build_independent_manifest(
    *,
    suite_id: str,
    source_build_id: str,
    eval_root: Path,
    dataset_dir: Path,
    previous_manifest_path: Path,
    previous_eval_root: Path,
    rag_jsonl: Path,
    prepared_by: str,
    approved_by: str,
    source_authority: str,
    code_commit: str,
) -> dict[str, Any]:
    if not SUITE_ID.fullmatch(suite_id):
        raise PipelineError("independent locked-suite ID must be a safe identifier")
    if len(source_build_id.strip()) < 3:
        raise PipelineError("independent locked suite requires a new source build identity")
    previous, previous_cases = _load_previous(previous_manifest_path, previous_eval_root)
    if suite_id == previous.get("suite_id"):
        raise PipelineError("independent locked suite must use a new suite ID")
    if source_build_id == previous.get("source_build_id"):
        raise PipelineError("independent locked suite must use a new source build identity")
    cases, input_hashes = load_locked_cases(
        eval_root,
        expected_source_build_id=source_build_id,
    )
    dataset_manifest, train_path, validation_path = _dataset_paths(dataset_dir)
    candidate_identity = locked_case_identity(cases)
    previous_identity = locked_case_identity(previous_cases)
    train_validation_identity = derived_dataset_identity((train_path, validation_path))
    isolation = isolation_summary(candidate_identity, train_validation_identity, previous_identity)
    if any(value != 0 for value in isolation.values()):
        failed = ", ".join(name for name, value in isolation.items() if value != 0)
        raise PipelineError(f"independent locked suite isolation failed: {failed}")
    prompt, _, hashes = frozen_prompt_contract()
    if not prompt:
        raise PipelineError("frozen Prompt is empty")
    declaration = {
        "prepared_by": prepared_by,
        "approved_by": approved_by,
        "frozen_at": utc_now(),
        "source_authority": source_authority,
        "training_team_had_content_access_before_freeze": False,
        "content_used_for_training_or_tuning": False,
        "generated_by_commit": code_commit,
    }
    _validate_independent_declaration(declaration)
    return {
        "schema_version": 2,
        "suite_id": suite_id,
        "source_build_id": source_build_id,
        "counts": {"jp": 92, "zh": 92, "total": 184},
        "input_hashes": input_hashes,
        "frozen_prompt_sha256": hashes["prompt_sha256"],
        "response_schema_sha256": hashes["response_schema_sha256"],
        "rag_train_sha256": sha256_file(rag_jsonl),
        "case_identity": _identity_summary(candidate_identity),
        "isolation": {
            "checks": isolation,
            "near_duplicate_threshold": NEAR_DUPLICATE_THRESHOLD,
            "train_validation_dataset_id": dataset_manifest["dataset_id"],
            "train_validation_manifest_sha256": sha256_file(dataset_dir / "dataset_manifest.json"),
            "train_jsonl_sha256": sha256_file(train_path),
            "validation_jsonl_sha256": sha256_file(validation_path),
            "previous_locked_suite_id": previous["suite_id"],
            "previous_locked_manifest_sha256": sha256_file(previous_manifest_path),
        },
        "independent_freeze": declaration,
        "policy": dict(POLICY),
    }


def validate_independent_manifest(
    manifest: dict[str, Any],
    *,
    cases: list[dict[str, Any]],
    input_hashes: dict[str, str],
    dataset_dir: Path,
    previous_manifest_path: Path,
    previous_eval_root: Path,
    rag_jsonl: Path | None,
) -> dict[str, Any]:
    expected_root = {
        "schema_version",
        "suite_id",
        "source_build_id",
        "counts",
        "input_hashes",
        "frozen_prompt_sha256",
        "response_schema_sha256",
        "rag_train_sha256",
        "case_identity",
        "isolation",
        "independent_freeze",
        "policy",
    }
    if set(manifest) != expected_root:
        raise PipelineError("independent suite manifest fields differ from the v2 contract")
    if manifest.get("schema_version") != 2:
        raise PipelineError("independent suite validation requires manifest schema version 2")
    suite_id = manifest.get("suite_id")
    if not isinstance(suite_id, str) or not SUITE_ID.fullmatch(suite_id):
        raise PipelineError("independent suite manifest ID is invalid")
    if manifest.get("counts") != {"jp": 92, "zh": 92, "total": 184}:
        raise PipelineError("independent suite manifest count contract is invalid")
    if manifest.get("input_hashes") != input_hashes:
        raise PipelineError("independent suite input files differ from the manifest")
    _, _, contract_hashes = frozen_prompt_contract()
    if manifest.get("frozen_prompt_sha256") != contract_hashes["prompt_sha256"]:
        raise PipelineError("independent suite Prompt identity mismatch")
    if manifest.get("response_schema_sha256") != contract_hashes["response_schema_sha256"]:
        raise PipelineError("independent suite Response Schema identity mismatch")
    if rag_jsonl is None or manifest.get("rag_train_sha256") != sha256_file(rag_jsonl):
        raise PipelineError("independent suite RAG identity mismatch")
    if manifest.get("policy") != POLICY:
        raise PipelineError("independent suite measurement-only policy mismatch")
    _validate_independent_declaration(manifest.get("independent_freeze"))
    previous, previous_cases = _load_previous(previous_manifest_path, previous_eval_root)
    if manifest.get("source_build_id") == previous.get("source_build_id"):
        raise PipelineError("independent suite reuses the previous source build identity")
    if suite_id == previous.get("suite_id"):
        raise PipelineError("independent suite reuses the previous suite ID")
    dataset_manifest, train_path, validation_path = _dataset_paths(dataset_dir)
    candidate_identity = locked_case_identity(cases)
    expected_case_identity = _identity_summary(candidate_identity)
    if manifest.get("case_identity") != expected_case_identity:
        raise PipelineError("independent suite case identity digest mismatch")
    isolation = isolation_summary(
        candidate_identity,
        derived_dataset_identity((train_path, validation_path)),
        locked_case_identity(previous_cases),
    )
    expected_isolation = {
        "checks": isolation,
        "near_duplicate_threshold": NEAR_DUPLICATE_THRESHOLD,
        "train_validation_dataset_id": dataset_manifest["dataset_id"],
        "train_validation_manifest_sha256": sha256_file(dataset_dir / "dataset_manifest.json"),
        "train_jsonl_sha256": sha256_file(train_path),
        "validation_jsonl_sha256": sha256_file(validation_path),
        "previous_locked_suite_id": previous["suite_id"],
        "previous_locked_manifest_sha256": sha256_file(previous_manifest_path),
    }
    if manifest.get("isolation") != expected_isolation:
        raise PipelineError("independent suite isolation evidence mismatch")
    if any(value != 0 for value in isolation.values()):
        raise PipelineError("independent suite is not isolated from training or previous locked data")
    return {
        "status": "pass",
        "suite_id": suite_id,
        "source_build_id": manifest["source_build_id"],
        "case_identity": expected_case_identity,
        "isolation_checks": isolation,
        "independent_freeze": manifest["independent_freeze"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build an independently frozen Meguri locked-suite manifest")
    parser.add_argument("--suite-id", required=True)
    parser.add_argument("--source-build-id", required=True)
    parser.add_argument("--eval-root", type=Path, required=True)
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--previous-locked-manifest", type=Path, required=True)
    parser.add_argument("--previous-locked-eval-root", type=Path, required=True)
    parser.add_argument("--rag-jsonl", type=Path, required=True)
    parser.add_argument("--prepared-by", required=True)
    parser.add_argument("--approved-by", required=True)
    parser.add_argument("--source-authority", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--acknowledge-independent-freeze-and-non-tuning", action="store_true")
    args = parser.parse_args()
    try:
        if not args.acknowledge_independent_freeze_and_non_tuning:
            raise PipelineError("independent freeze and non-tuning acknowledgement is required")
        commit = require_clean_git_worktree()
        require_git_tracked_file(args.previous_locked_manifest)
        manifest = build_independent_manifest(
            suite_id=args.suite_id,
            source_build_id=args.source_build_id,
            eval_root=args.eval_root,
            dataset_dir=args.dataset_dir,
            previous_manifest_path=args.previous_locked_manifest,
            previous_eval_root=args.previous_locked_eval_root,
            rag_jsonl=args.rag_jsonl,
            prepared_by=args.prepared_by,
            approved_by=args.approved_by,
            source_authority=args.source_authority,
            code_commit=commit,
        )
        write_json(args.output, manifest)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "manifest": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
