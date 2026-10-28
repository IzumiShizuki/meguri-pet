"""Explicit, offline user supervision: import, human review, then export.

Sources are read-only. This module neither trains a model nor reads locked cases.
Pattern redaction is only a first pass; a reviewer must also check privacy.
"""
from __future__ import annotations

import json
import os
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from services.meguri_core.schemas import LlmResponse
from .common import (
    ARTIFACT_ROOT, LLM_ROOT, PipelineError, SOURCE_BUILD_ID, canonical_json,
    git_commit, read_json, read_jsonl, sha256_file, sha256_text, utc_now,
    write_json, write_jsonl,
)
from .dataset import MODE_BY_OUTFIT, _response_contract, _runtime_block, load_locked_scene_ids


VERSION = "meguri-feedback-bridge-v1"
IMPORT_KIND = "meguri-feedback-import-v1"
REVIEW_KIND = "meguri-feedback-review-v1"
DATASET_KIND = "meguri-feedback-augmented-sft-v1"
BASELINE_ANCHOR = LLM_ROOT / "datasets" / "meguri-text-sft-v1-532aca8b1a5d" / "dataset_manifest.json"
RESPONSE_FIELDS = {"reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates"}
SAFE_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,159}$")
REDACTIONS = (
    ("credential", re.compile(r"(?i)\bBearer\s+[a-z0-9._~+/=-]{8,}")),
    ("credential", re.compile(r"\bsk-[a-zA-Z0-9_-]{10,}")),
    ("credential", re.compile(r"(?i)\b(?:password|passwd|api[_-]?key|secret|token)\s*[:=]\s*[^\s,;]+")),
    ("credential", re.compile(r"(?:密码|口令|密钥)\s*[:：=]\s*[^\s,，;；]+")),
    ("email", re.compile(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}")),
    ("phone", re.compile(r"(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)")),
    ("phone", re.compile(r"(?<!\w)\+\d(?:[ -]?\d){7,14}(?!\d)")),
    ("identity_number", re.compile(r"(?<!\d)\d{17}[\dXx](?!\w)")),
    ("network_address", re.compile(r"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)")),
)


def _identifier(value: Any, field: str) -> str:
    if not isinstance(value, str) or not SAFE_ID.fullmatch(value):
        raise PipelineError(f"missing or unsupported {field}")
    return value


def _text(value: Any, field: str, limit: int = 20000) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise PipelineError(f"missing or unsupported {field}")
    return value


def redact_text(value: str, counts: Counter[str] | None = None) -> str:
    result = value
    for kind, pattern in REDACTIONS:
        result, number = pattern.subn(f"[REDACTED_{kind.upper()}]", result)
        if counts is not None:
            counts[kind] += number
    return result


def _response(value: Any, counts: Counter[str] | None = None) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != RESPONSE_FIELDS:
        raise PipelineError("response must contain exactly the pinned response-contract fields")
    try:
        validated = LlmResponse.model_validate(value).model_dump(mode="json")
    except ValidationError as exc:
        raise PipelineError("response violates the pinned runtime response contract") from exc
    validated["reply"] = redact_text(_text(validated["reply"], "response.reply"), counts)
    if counts is not None:
        counts["memory_candidates_removed"] += len(validated["memory_candidates"])
    # Personal memory extraction is not a behavioral training target.
    validated["memory_candidates"] = []
    return validated


def _normalized(value: Any) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", canonical_json(value))).casefold()


def _digest(row: dict[str, Any]) -> str:
    value = {key: item for key, item in row.items() if key != "candidate_digest"}
    # An appended source log must not change the review of an unchanged record.
    # The record hash still binds all raw fields; locations belong to the import.
    value["provenance"] = {key: item for key, item in value.get("provenance", {}).items()
                           if key not in {"source_ref", "source_line", "source_sha256"}}
    return sha256_text(canonical_json(value))


def _source_record(source: dict[str, Any], *, legacy: bool, source_ref: str, line: int,
                   source_hash: str) -> dict[str, Any]:
    feedback_id = _identifier(source.get("feedback_id"), "feedback_id")
    schema = source.get("schema_version")
    provenance = source.get("provenance")
    if not isinstance(provenance, dict) or provenance.get("source") != "desktop_active_learning":
        raise PipelineError("unknown feedback service provenance")
    for field in ("model_provider", "build_id", "comparison_id"):
        _text(provenance.get(field), f"provenance.{field}", 160)
    if source.get("status") != "training_candidate":
        raise PipelineError("unsupported feedback status")
    if schema == "meguri-behavior-feedback-v2":
        if source.get("consent_to_training") is not True or source.get("consent_version") != "meguri-training-consent-v1":
            raise PipelineError("explicit training consent is missing")
        _text(source.get("consent_captured_at"), "consent_captured_at", 100)
        consent = "explicit_v2"
    elif schema == "meguri-behavior-feedback-v1" and legacy and "consent_to_training" not in source:
        # Only this known service's v1 capture enforced consent before appending.
        # Import still requires operator opt-in and a later human approval.
        if not str(provenance["comparison_id"]).startswith(("comparison-", "observation-")):
            raise PipelineError("unknown legacy service comparison provenance")
        consent = "operator_attested_known_v1_service"
    else:
        raise PipelineError("unsupported schema or missing explicit consent")
    for field in ("user_id", "client_id", "session_id", "turn_id"):
        _identifier(source.get(field), field)
    tenant = _identifier(source.get("tenant_id", "meguri-local"), "tenant_id")
    counts: Counter[str] = Counter()
    user_message = redact_text(_text(source.get("user_message"), "user_message"), counts)
    context = source.get("recent_context", [])
    if not isinstance(context, list) or len(context) > 12 or any(not isinstance(item, str) or len(item) > 20000 for item in context):
        raise PipelineError("unsupported recent_context")
    context = [redact_text(item, counts) for item in context]
    candidates = source.get("candidates")
    if not isinstance(candidates, list) or not 1 <= len(candidates) <= 2:
        raise PipelineError("one or two response candidates are required")
    cleaned = []
    seen: set[str] = set()
    for candidate in candidates:
        if not isinstance(candidate, dict):
            raise PipelineError("invalid response candidate")
        candidate_id = _identifier(candidate.get("candidate_id"), "candidate_id")
        if candidate_id in seen:
            raise PipelineError("duplicate candidate_id")
        seen.add(candidate_id)
        cleaned.append({"candidate_id": candidate_id, "response": _response(candidate.get("response"), counts)})
    selected = source.get("selected_candidate_id")
    if selected is not None and selected not in seen:
        raise PipelineError("selected candidate is unavailable")
    note = source.get("feedback_text", "")
    if not isinstance(note, str) or len(note) > 2000:
        raise PipelineError("unsupported feedback_text")
    corrected = source.get("corrected_response")
    corrected = _response(corrected, counts) if corrected is not None else None
    if selected is None and corrected is None and not note.strip():
        raise PipelineError("feedback has no selection, correction or review note")
    scope = [tenant, source["user_id"]]
    groups = ["conversation:" + sha256_text(canonical_json([*scope, source["session_id"]]))]
    parent = source.get("parent_session_id")
    if parent:
        groups.append("conversation:" + sha256_text(canonical_json([*scope, _identifier(parent, "parent_session_id")])))
    scene = source.get("scene_id")
    if scene is not None:
        scene = _identifier(scene, "scene_id")
        groups.append("scene:" + scene)
    if source.get("relationship_profile") not in {None, "sibling", "pursuit", "lover"} or source.get("reply_format", "default") not in {"default", "zh_ja_pairs"}:
        raise PipelineError("unsupported captured relationship or reply format")
    result = {
        "schema_version": IMPORT_KIND, "feedback_id": feedback_id, "status": "pending_review",
        "consent_basis": consent, "user_message": user_message, "recent_context": context,
        "candidates": cleaned, "selected_candidate_id": selected,
        "corrected_response": corrected, "feedback_text": redact_text(note, counts),
        "group_tokens": sorted(set(groups)), "scene_id": scene,
        "captured_relationship": source.get("relationship_profile"),
        "reply_format": source.get("reply_format", "default"),
        "redactions": dict(sorted((key, value) for key, value in counts.items() if value)),
        "provenance": {
            "source_ref": source_ref, "source_line": line, "source_sha256": source_hash,
            "source_record_sha256": sha256_text(canonical_json(source)),
            "source_schema": schema, "source_service": "desktop_active_learning",
            "capture_build_id": redact_text(provenance["build_id"]),
            "model_provider": redact_text(provenance["model_provider"]),
            "turn_id_sha256": sha256_text(canonical_json([*scope, source["turn_id"]])),
        },
    }
    result["candidate_digest"] = _digest(result)
    return result


def _path_guard(output: Path, sources: list[Path]) -> None:
    protected = [path.resolve() for path in sources] + [
        (LLM_ROOT.parents[1] / "datasets").resolve(), (LLM_ROOT.parents[1] / "data").resolve(),
    ]
    for source in protected:
        target = source if source.is_dir() else source.parent
        if output.resolve() == target or output.resolve().is_relative_to(target):
            raise PipelineError("derived output must be outside read-only source and canonical data directories")


def import_feedback(sources: list[Path], output_root: Path = ARTIFACT_ROOT / "feedback",
                    *, allow_legacy_service_provenance: bool = False) -> Path:
    if not sources or len({path.resolve() for path in sources}) != len(sources):
        raise PipelineError("distinct feedback source files are required")
    _path_guard(output_root, sources)
    source_info = [{"path": str(path.resolve()), "sha256": sha256_file(path)} for path in sorted(sources, key=lambda item: str(item.resolve()))]
    events = [(info, line, row) for info in source_info for line, row in read_jsonl(Path(info["path"]))]
    revoked: set[str] = set()
    conflicts: set[str] = set()
    raw_hashes: dict[str, str] = {}
    duplicates = 0
    # Withdrawal is terminal regardless of event ordering or repeated snapshots.
    for _, _, row in events:
        feedback_id = _identifier(row.get("feedback_id"), "feedback_id")
        if row.get("status") == "revoked":
            if row.get("schema_version") != "meguri-behavior-feedback-v2" or row.get("consent_to_training") is not False:
                raise PipelineError("unsupported withdrawal marker")
            revoked.add(feedback_id)
            continue
        value = sha256_text(canonical_json(row))
        if feedback_id in raw_hashes:
            if raw_hashes[feedback_id] != value:
                conflicts.add(feedback_id)
            else:
                duplicates += 1
        raw_hashes[feedback_id] = value
    accepted: dict[str, dict[str, Any]] = {}
    exclusions: Counter[str] = Counter()
    exclusions["duplicate_source_events"] = duplicates
    for info, line, row in events:
        feedback_id = row["feedback_id"]
        if feedback_id in revoked or feedback_id in conflicts or row.get("status") == "revoked":
            continue
        if feedback_id in accepted:
            continue
        try:
            accepted[feedback_id] = _source_record(row, legacy=allow_legacy_service_provenance,
                source_ref=info["sha256"], line=line, source_hash=info["sha256"])
        except PipelineError as exc:
            exclusions[str(exc)] += 1
    exclusions["revoked_feedback"] = len(revoked)
    exclusions["conflicting_feedback_ids"] = len(conflicts)
    # Refuse a torn/concurrently appended input instead of claiming a stable hash.
    _check_fresh_sources(source_info)
    identity = {"version": VERSION, "sources": source_info, "legacy_opt_in": allow_legacy_service_provenance}
    output = output_root.resolve() / ("feedback-import-" + sha256_text(canonical_json(identity))[:16])
    if output.exists():
        raise PipelineError("refusing to overwrite existing feedback import")
    output.mkdir(parents=True)
    write_jsonl(output / "pending.jsonl", [accepted[key] for key in sorted(accepted)])
    report = {"pending_count": len(accepted), "exclusions": dict(sorted(exclusions.items())),
              "revoked_feedback_ids": sorted(revoked), "raw_content_exported": False,
              "privacy_policy": "pattern_redaction_then_explicit_human_privacy_review"}
    write_json(output / "import_report.json", report)
    write_json(output / "import_manifest.json", {"schema_version": IMPORT_KIND, **identity,
        "created_at": utc_now(), "files": {name: sha256_file(output / name) for name in ("pending.jsonl", "import_report.json")},
        "pending_count": len(accepted), "auto_approved": False})
    return output


def _check_fresh_sources(sources: list[dict[str, Any]]) -> None:
    if not sources:
        raise PipelineError("feedback source freshness evidence is missing")
    for source in sources:
        if sha256_file(Path(source["path"])) != source["sha256"]:
            raise PipelineError("feedback source changed or was withdrawn; reimport and rebuild the dataset")


def _load_import(import_dir: Path, *, fresh: bool = True) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest = read_json(import_dir / "import_manifest.json")
    if manifest.get("schema_version") != IMPORT_KIND or manifest.get("auto_approved") is not False:
        raise PipelineError("unsupported or automatically approved feedback import")
    for name in ("pending.jsonl", "import_report.json"):
        if manifest.get("files", {}).get(name) != sha256_file(import_dir / name):
            raise PipelineError("feedback import hash mismatch")
    if fresh:
        _check_fresh_sources(manifest.get("sources", []))
    rows = [row for _, row in read_jsonl(import_dir / "pending.jsonl")]
    if len(rows) != manifest.get("pending_count") or any(row.get("candidate_digest") != _digest(row) for row in rows):
        raise PipelineError("feedback import candidate digest mismatch")
    return manifest, rows


def _runtime(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        raise PipelineError("explicit reviewed runtime metadata is required")
    language = value.get("language")
    relationship = value.get("relationship_stage")
    outfit = value.get("outfit_code")
    mode = value.get("interaction_mode")
    if language not in {"zh", "ja"} or relationship not in {"sibling", "pursuit", "lover"}:
        raise PipelineError("unsupported reviewed language or relationship")
    if outfit not in MODE_BY_OUTFIT or mode != MODE_BY_OUTFIT[outfit]:
        raise PipelineError("reviewed outfit/mode must match the pinned deterministic outfit map")
    return {"language": language, "relationship_stage": relationship, "outfit_code": outfit, "interaction_mode": mode}


def _target(row: dict[str, Any], review: dict[str, Any]) -> tuple[dict[str, Any], str]:
    corrected = review.get("corrected_response", row.get("corrected_response"))
    if corrected is not None:
        return _response(corrected), "structured_correction"
    selected = row.get("selected_candidate_id")
    chosen = next((item["response"] for item in row["candidates"] if item["candidate_id"] == selected), None)
    if chosen is None:
        raise PipelineError("a free-text note is review evidence, not a corrected training target")
    return _response(chosen), "selected_response"


def _valid_review(row: dict[str, Any], review: dict[str, Any]) -> None:
    if review.get("schema_version") != REVIEW_KIND or review.get("candidate_digest") != row["candidate_digest"]:
        raise PipelineError("review must bind the current candidate digest")
    _text(review.get("reviewer"), "reviewer", 160)
    if redact_text(review["reviewer"]) != review["reviewer"]:
        raise PipelineError("use a nonsensitive reviewer alias")
    _text(review.get("reviewed_at"), "reviewed_at", 100)
    if review.get("decision") not in {"approve", "reject", "revoke"}:
        raise PipelineError("unsupported review decision")
    if review["decision"] == "approve":
        if review.get("privacy_reviewed") is not True:
            raise PipelineError("explicit human privacy review is required")
        _runtime(review.get("runtime"))
        _target(row, review)
    if review.get("scene_id") is not None:
        _identifier(review["scene_id"], "review scene_id")


def record_review(import_dir: Path, ledger: Path, *, feedback_id: str, candidate_digest: str,
                  decision: str, reviewer: str, privacy_reviewed: bool = False,
                  runtime: dict[str, str] | None = None,
                  corrected_response: dict[str, Any] | None = None, scene_id: str | None = None) -> dict[str, Any]:
    manifest, rows = _load_import(import_dir)
    _path_guard(ledger, [Path(item["path"]) for item in manifest["sources"]] + [import_dir])
    row = next((item for item in rows if item["feedback_id"] == feedback_id), None)
    if row is None:
        raise PipelineError("feedback candidate is unavailable or withdrawn")
    review = {"schema_version": REVIEW_KIND, "feedback_id": feedback_id,
              "candidate_digest": candidate_digest, "decision": decision, "reviewer": reviewer,
              "reviewed_at": utc_now(), "privacy_reviewed": privacy_reviewed, "runtime": runtime}
    if corrected_response is not None:
        review["corrected_response"] = _response(corrected_response)
    if scene_id is not None:
        review["scene_id"] = scene_id
    _valid_review(row, review)
    ledger.parent.mkdir(parents=True, exist_ok=True)
    with ledger.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(canonical_json(review) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    return review


def _reviews(ledger: Path, rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    candidates = {row["feedback_id"]: row for row in rows}
    result = {}
    for _, review in read_jsonl(ledger):
        feedback_id = _identifier(review.get("feedback_id"), "review feedback_id")
        if feedback_id not in candidates:
            continue  # Historical decisions for revoked or older imports remain auditable.
        if review.get("candidate_digest") != candidates[feedback_id]["candidate_digest"]:
            continue  # A changed candidate needs a fresh digest-bound decision.
        _valid_review(candidates[feedback_id], review)
        result[feedback_id] = review
    return result


def _baseline(dataset_dir: Path) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]], str]:
    from .training_utils import validate_dataset_for_training
    manifest, _ = validate_dataset_for_training(dataset_dir)
    anchor = read_json(BASELINE_ANCHOR)
    if anchor.get("source_build_id") != SOURCE_BUILD_ID or (anchor.get("train_count"), anchor.get("validation_count")) != (2626, 566):
        raise PipelineError("canonical baseline approval anchor is invalid")
    if manifest.get("dataset_kind") is not None or manifest.get("source_build_id") != SOURCE_BUILD_ID:
        raise PipelineError("feedback parent must be the canonical B baseline, not another augmented dataset")
    for field in ("dataset_id", "source_build_id", "input_hashes", "prompt_sha256", "response_schema_sha256", "split_metadata_sha256"):
        if manifest.get(field) != anchor.get(field):
            raise PipelineError("feedback parent does not match the approved canonical baseline")
    rows = {split: [row for _, row in read_jsonl(dataset_dir / f"{split}.jsonl")] for split in ("train", "validation")}
    for split, values in rows.items():
        if manifest["files"][f"{split}.jsonl"] != anchor["files"][f"{split}.jsonl"] or len(values) != anchor[f"{split}_count"]:
            raise PipelineError("feedback parent baseline content/count differs from the approved B baseline")
    try:
        prompt = rows["train"][0]["messages"][0]["content"].rsplit("\n\n<RUNTIME_STATE>", 1)[0]
    except (KeyError, IndexError, TypeError) as exc:
        raise PipelineError("baseline has no pinned system prompt") from exc
    if sha256_text(prompt) != anchor["prompt_sha256"]:
        raise PipelineError("baseline system prompt hash mismatch")
    if _response_contract()[1] != anchor["response_schema_sha256"]:
        raise PipelineError("response contract changed; approve a new baseline before augmenting feedback")
    return manifest, rows, prompt


def _input_text(row: dict[str, Any]) -> str:
    if not row["recent_context"]:
        return row["user_message"]
    return "<RECENT_CONVERSATION>\n" + "\n".join(row["recent_context"]) + "\n</RECENT_CONVERSATION>\n\n" + row["user_message"]


def _sample_key(user: str, target: dict[str, Any]) -> str:
    return sha256_text(_normalized({"user": user, "target": target}))


def _groups(rows: list[dict[str, Any]], reviews: dict[str, dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    parent: dict[str, str] = {}
    def find(token: str) -> str:
        parent.setdefault(token, token)
        while parent[token] != token:
            parent[token] = parent[parent[token]]
            token = parent[token]
        return token
    for row in rows:
        tokens = list(row["group_tokens"])
        review = reviews.get(row["feedback_id"])
        if review and review.get("scene_id"):
            tokens.append("scene:" + review["scene_id"])
        if review and review["decision"] == "approve":
            tokens.append("sample:" + _sample_key(_input_text(row), _target(row, review)[0]))
        row["export_group_tokens"] = sorted(set(tokens))
        for token in tokens:
            left, right = find(tokens[0]), find(token)
            parent[max(left, right)] = min(left, right)
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[find(row["export_group_tokens"][0])].append(row)
    return groups


def build_feedback_dataset(*, import_dir: Path, ledger: Path, baseline_dir: Path,
                           split_root: Path, output_root: Path = ARTIFACT_ROOT / "datasets",
                           validation_fraction: float = 0.15, split_seed: str = "meguri-feedback-split-v1",
                           previous_dataset: Path | None = None) -> Path:
    if not 0 < validation_fraction < 1:
        raise PipelineError("validation fraction must be between zero and one")
    _path_guard(output_root, [baseline_dir, import_dir, ledger])
    imported, rows = _load_import(import_dir)
    ledger_hash = sha256_file(ledger)
    reviews = _reviews(ledger, rows)
    baseline, baseline_rows, prompt = _baseline(baseline_dir)
    _, schema_hash, tags = _response_contract()
    locked = load_locked_scene_ids(split_root)
    locked_hash = sha256_file(split_root / "test_scene_ids.txt")
    previous = read_json(previous_dataset / "dataset_manifest.json") if previous_dataset else {}
    if previous and previous.get("dataset_kind") != DATASET_KIND:
        raise PipelineError("previous split assignments must come from a feedback dataset manifest")
    old_splits = previous.get("group_token_splits", {})
    if any(value not in {"train", "validation"} for value in old_splits.values()):
        raise PipelineError("invalid previous split assignments")
    known: dict[str, set[str]] = defaultdict(set)
    for split, values in baseline_rows.items():
        for row in values:
            metadata = row.get("metadata", {})
            if metadata.get("scene_id"):
                known["scene:" + metadata["scene_id"]].add(split)
            known["sample:" + _sample_key(row["messages"][-2]["content"], json.loads(row["messages"][-1]["content"]))].add(split)
    exports: dict[str, list[dict[str, Any]]] = {"train": [], "validation": []}
    preferences = {"train": [], "validation": []}
    exclusions: Counter[str] = Counter()
    assignments = dict(old_splits)
    exported_ids = []
    seen: set[str] = set()
    for group, members in sorted(_groups(rows, reviews).items()):
        tokens = sorted({token for row in members for token in row["export_group_tokens"]})
        if any(token.removeprefix("scene:") in locked for token in tokens if token.startswith("scene:")):
            exclusions["locked_scene_group"] += len(members)
            continue
        pinned = {old_splits[token] for token in tokens if token in old_splits}
        pinned.update(split for token in tokens for split in known.get(token, set()))
        if len(pinned) > 1:
            exclusions["conflicting_existing_split_group"] += len(members)
            continue
        fraction = int(sha256_text(split_seed + ":" + group)[:16], 16) / 2 ** 64
        split = next(iter(pinned)) if pinned else ("validation" if fraction < validation_fraction else "train")
        group_id = sha256_text(canonical_json(tokens))
        for token in tokens:
            assignments[token] = split
        for row in sorted(members, key=lambda item: item["feedback_id"]):
            review = reviews.get(row["feedback_id"])
            if review is None or review["decision"] != "approve":
                exclusions["unapproved_or_review_revoked"] += 1
                continue
            target, target_kind = _target(row, review)
            user = _input_text(row)
            key = _sample_key(user, target)
            if key in seen or "sample:" + key in known:
                exclusions["duplicate_sft_target"] += 1
                continue
            seen.add(key)
            runtime = _runtime(review["runtime"])
            block, _ = _runtime_block(runtime, tags)
            review_hash = sha256_text(canonical_json(review))
            metadata = {**runtime, "sample_id": "feedback-" + row["feedback_id"],
                "scene_id": row.get("scene_id") or review.get("scene_id") or "feedback-group-" + group_id,
                "line_id": "feedback-" + row["feedback_id"], "data_build_id": SOURCE_BUILD_ID,
                "source_split": split, "feedback_group_id": group_id, "feedback_group_tokens": tokens,
                "prompt_sha256": baseline["prompt_sha256"], "response_schema_sha256": schema_hash,
                "converter_version": VERSION, "interaction_mode_source": "human_reviewed_runtime_v1",
                "provenance": {**row["provenance"], "feedback_id": row["feedback_id"],
                    "candidate_digest": row["candidate_digest"], "review_sha256": review_hash,
                    "reviewer_sha256": sha256_text(review["reviewer"]), "consent_basis": row["consent_basis"],
                    "privacy_reviewed": True, "target_kind": target_kind}}
            messages = [{"role": "system", "content": prompt + "\n\n" + block},
                        {"role": "user", "content": user}, {"role": "assistant", "content": canonical_json(target)}]
            exports[split].append({"messages": messages, "metadata": metadata})
            exported_ids.append(row["feedback_id"])
            for candidate in row["candidates"]:
                rejected = candidate["response"]
                if _normalized(rejected) == _normalized(target):
                    continue
                preferences[split].append({"prompt": messages[:-1], "chosen": canonical_json(target),
                    "rejected": canonical_json(rejected), "metadata": {**metadata,
                        "rejected_candidate_id": candidate["candidate_id"]}})
    _check_fresh_sources(imported["sources"])
    if sha256_file(ledger) != ledger_hash:
        raise PipelineError("review ledger changed during export")
    identity = {"version": VERSION, "baseline_manifest_sha256": sha256_file(baseline_dir / "dataset_manifest.json"),
                "import_manifest_sha256": sha256_file(import_dir / "import_manifest.json"),
                "review_ledger_sha256": ledger_hash, "locked_scene_metadata_sha256": locked_hash,
                "split_seed": split_seed, "validation_fraction": validation_fraction,
                "previous_group_splits_sha256": sha256_text(canonical_json(old_splits))}
    dataset_id = "meguri-feedback-sft-v1-" + sha256_text(canonical_json(identity))[:16]
    output = output_root.resolve() / dataset_id
    if output.exists():
        raise PipelineError("refusing to overwrite existing feedback dataset")
    output.mkdir(parents=True)
    for split in ("train", "validation"):
        write_jsonl(output / f"{split}.jsonl", baseline_rows[split] + exports[split])
        write_jsonl(output / f"preferences_{split}.jsonl", preferences[split])
    write_json(output / "baseline_manifest.json", baseline)
    write_json(output / "feedback_import_manifest.json", imported)
    write_jsonl(output / "approved_reviews.jsonl", [reviews[key] for key in sorted(exported_ids)])
    write_jsonl(output / "approved_candidates.jsonl", [{key: value for key, value in row.items() if key != "export_group_tokens"}
        for row in sorted(rows, key=lambda item: item["feedback_id"]) if row["feedback_id"] in set(exported_ids)])
    quality = {"schema_version": 1, "status": "pass", "dataset_id": dataset_id,
               "feedback_counts": {split: len(exports[split]) for split in exports},
               "preference_counts": {split: len(preferences[split]) for split in preferences},
               "exclusions": dict(sorted(exclusions.items())), "locked_eval_content_read": False,
               "split_policy": "conversation_scene_duplicate_connected_groups_with_pinned_prior_assignments"}
    write_json(output / "quality_report.json", quality)
    names = ["train.jsonl", "validation.jsonl", "preferences_train.jsonl", "preferences_validation.jsonl",
             "baseline_manifest.json", "feedback_import_manifest.json", "approved_reviews.jsonl", "approved_candidates.jsonl", "quality_report.json"]
    manifest = {"schema_version": 1, "dataset_kind": DATASET_KIND, "dataset_id": dataset_id,
        "source_build_id": SOURCE_BUILD_ID, "created_at": utc_now(), "conversion_commit": git_commit(),
        "converter_version": VERSION, "identity": identity, "quality_gate_status": "pass",
        "train_count": baseline["train_count"] + len(exports["train"]),
        "validation_count": baseline["validation_count"] + len(exports["validation"]),
        "baseline_counts": {split: baseline[f"{split}_count"] for split in exports},
        "feedback_counts": quality["feedback_counts"], "preference_counts": quality["preference_counts"],
        "baseline_dataset_id": baseline["dataset_id"], "baseline_file_hashes": {split: baseline["files"][f"{split}.jsonl"] for split in exports},
        "prompt_sha256": baseline["prompt_sha256"], "response_schema_sha256": schema_hash,
        "languages": ["ja", "zh"], "locked_eval_count": baseline["locked_eval_count"],
        "locked_eval_policy": {"content_read_by_converter": False, "used_for_training": False, "used_for_prompt_tuning": False},
        "locked_scene_ids": sorted(locked), "group_token_splits": dict(sorted(assignments.items())),
        "sources": imported["sources"], "review_ledger": {"path": str(ledger.resolve()), "sha256": ledger_hash},
        "exported_feedback_ids": sorted(exported_ids), "files": {name: sha256_file(output / name) for name in names}}
    write_json(output / "dataset_manifest.json", manifest)
    validate_feedback_dataset(output)
    return output


def validate_feedback_dataset(dataset_dir: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = read_json(dataset_dir / "dataset_manifest.json")
    if manifest.get("dataset_kind") != DATASET_KIND or manifest.get("source_build_id") != SOURCE_BUILD_ID:
        raise PipelineError("unsupported feedback dataset kind or source build")
    names = {"train.jsonl", "validation.jsonl", "preferences_train.jsonl", "preferences_validation.jsonl",
             "baseline_manifest.json", "feedback_import_manifest.json", "approved_reviews.jsonl", "approved_candidates.jsonl", "quality_report.json"}
    if set(manifest.get("files", {})) != names:
        raise PipelineError("feedback dataset artifact hash inventory is incomplete")
    for name in names:
        if sha256_file(dataset_dir / name) != manifest["files"][name]:
            raise PipelineError(f"feedback dataset hash mismatch: {name}")
    identity = manifest.get("identity", {})
    if manifest.get("dataset_id") != "meguri-feedback-sft-v1-" + sha256_text(canonical_json(identity))[:16]:
        raise PipelineError("feedback dataset identity mismatch")
    policy = manifest.get("locked_eval_policy", {})
    if any(policy.get(key) is not False for key in ("content_read_by_converter", "used_for_training", "used_for_prompt_tuning")):
        raise PipelineError("feedback dataset does not prove locked evaluation isolation")
    _check_fresh_sources(manifest.get("sources", []))
    ledger = manifest.get("review_ledger", {})
    if sha256_file(Path(str(ledger.get("path", "")))) != ledger.get("sha256"):
        raise PipelineError("review ledger changed; rebuild the feedback dataset")
    imported = read_json(dataset_dir / "feedback_import_manifest.json")
    if imported.get("schema_version") != IMPORT_KIND or imported.get("sources") != manifest["sources"] or imported.get("auto_approved") is not False:
        raise PipelineError("feedback import provenance mismatch")
    if manifest["files"]["baseline_manifest.json"] != identity.get("baseline_manifest_sha256") or manifest["files"]["feedback_import_manifest.json"] != identity.get("import_manifest_sha256") or ledger["sha256"] != identity.get("review_ledger_sha256"):
        raise PipelineError("feedback input provenance hashes do not match dataset identity")
    baseline = read_json(dataset_dir / "baseline_manifest.json")
    anchor = read_json(BASELINE_ANCHOR)
    if anchor.get("source_build_id") != SOURCE_BUILD_ID or (anchor.get("train_count"), anchor.get("validation_count")) != (2626, 566):
        raise PipelineError("canonical baseline approval anchor is invalid")
    if baseline.get("dataset_kind") is not None or baseline.get("dataset_id") != anchor["dataset_id"] or baseline.get("source_build_id") != SOURCE_BUILD_ID:
        raise PipelineError("unverified canonical baseline parent")
    for field in ("input_hashes", "prompt_sha256", "response_schema_sha256", "split_metadata_sha256"):
        if baseline.get(field) != anchor.get(field) or manifest.get(field, baseline.get(field)) != anchor.get(field):
            raise PipelineError("unverified baseline source/prompt/response contract")
    candidates = [row for _, row in read_jsonl(dataset_dir / "approved_candidates.jsonl")]
    if len({row.get("feedback_id") for row in candidates}) != len(candidates) or any(row.get("candidate_digest") != _digest(row) for row in candidates):
        raise PipelineError("approved candidate digest mismatch")
    decisions = _reviews(dataset_dir / "approved_reviews.jsonl", candidates)
    latest = _reviews(Path(ledger["path"]), candidates)
    by_id = {row["feedback_id"]: row for row in candidates}
    seen_ids: set[str] = set()
    exported_samples: dict[str, dict[str, Any]] = {}
    token_splits: dict[str, str] = {}
    rows_by_split = {split: [row for _, row in read_jsonl(dataset_dir / f"{split}.jsonl")]
                     for split in ("train", "validation")}
    try:
        baseline_keys = {_sample_key(row["messages"][-2]["content"], json.loads(row["messages"][-1]["content"]))
                         for split, values in rows_by_split.items() for row in values[:anchor[f"{split}_count"]]}
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        raise PipelineError("invalid baseline prefix") from exc
    feedback_keys: set[str] = set()
    baseline_scene_splits: dict[str, set[str]] = defaultdict(set)
    for baseline_split, values in rows_by_split.items():
        for baseline_row in values[:anchor[f"{baseline_split}_count"]]:
            scene = baseline_row.get("metadata", {}).get("scene_id")
            if scene:
                baseline_scene_splits["scene:" + scene].add(baseline_split)
    for split in ("train", "validation"):
        rows = rows_by_split[split]
        count = anchor[f"{split}_count"]
        if baseline.get(f"{split}_count") != count or manifest.get("baseline_counts", {}).get(split) != count:
            raise PipelineError("baseline counts do not match the approved 2626/566 baseline")
        prefix = "".join(canonical_json(row) + "\n" for row in rows[:count])
        expected = anchor["files"][f"{split}.jsonl"]
        if sha256_text(prefix) != expected or baseline.get("files", {}).get(f"{split}.jsonl") != expected or manifest.get("baseline_file_hashes", {}).get(split) != expected:
            raise PipelineError("approved baseline prefix was altered")
        if len(rows) != manifest.get(f"{split}_count") or len(rows) - count != manifest.get("feedback_counts", {}).get(split):
            raise PipelineError("feedback dataset count mismatch")
        for row in rows:
            if [item.get("role") for item in row.get("messages", [])] != ["system", "user", "assistant"]:
                raise PipelineError("SFT row must contain exactly system, user, assistant messages")
            try:
                response = json.loads(row["messages"][-1]["content"])
            except (TypeError, json.JSONDecodeError) as exc:
                raise PipelineError("SFT response JSON is invalid") from exc
            _response(response)
            metadata = row.get("metadata", {})
            if metadata.get("scene_id") in manifest.get("locked_scene_ids", []):
                raise PipelineError("locked evaluation scene entered the feedback dataset")
        for row in rows[count:]:
            metadata = row["metadata"]
            provenance = metadata.get("provenance", {})
            feedback_id = provenance.get("feedback_id")
            candidate = by_id.get(feedback_id)
            review = decisions.get(feedback_id)
            if candidate is None or review is None or review.get("decision") != "approve" or review != latest.get(feedback_id):
                raise PipelineError("feedback target lacks current explicit human approval")
            if feedback_id in seen_ids:
                raise PipelineError("duplicate exported feedback ID")
            seen_ids.add(feedback_id)
            exported_samples[feedback_id] = row
            if provenance.get("candidate_digest") != candidate["candidate_digest"] or provenance.get("review_sha256") != sha256_text(canonical_json(review)) or provenance.get("privacy_reviewed") is not True:
                raise PipelineError("feedback review provenance mismatch")
            target, _ = _target(candidate, review)
            key = _sample_key(row["messages"][-2]["content"], target)
            if key in baseline_keys or key in feedback_keys:
                raise PipelineError("duplicate feedback target entered the dataset")
            feedback_keys.add(key)
            runtime = _runtime(review["runtime"])
            block, _ = _runtime_block(runtime, _response_contract()[2])
            prompt = rows[0]["messages"][0]["content"].rsplit("\n\n<RUNTIME_STATE>", 1)[0]
            expected_messages = [{"role": "system", "content": prompt + "\n\n" + block},
                {"role": "user", "content": _input_text(candidate)}, {"role": "assistant", "content": canonical_json(target)}]
            if row["messages"] != expected_messages or sha256_text(prompt) != anchor["prompt_sha256"]:
                raise PipelineError("exported SFT target/input differs from approved sanitized feedback")
            tokens = metadata.get("feedback_group_tokens")
            if not isinstance(tokens, list) or not tokens or any(not isinstance(token, str) for token in tokens):
                raise PipelineError("feedback group evidence is missing")
            required_tokens = set(candidate["group_tokens"])
            required_tokens.add("sample:" + _sample_key(_input_text(candidate), target))
            if review.get("scene_id"):
                required_tokens.add("scene:" + review["scene_id"])
            if not required_tokens.issubset(tokens) or tokens != sorted(set(tokens)):
                raise PipelineError("feedback group evidence differs from approved source/review")
            group_id = sha256_text(canonical_json(tokens))
            expected_scene = candidate.get("scene_id") or review.get("scene_id") or "feedback-group-" + group_id
            if (metadata.get("feedback_group_id") != group_id or metadata.get("source_split") != split
                    or metadata.get("scene_id") != expected_scene
                    or any(metadata.get(field) != value for field, value in runtime.items())):
                raise PipelineError("feedback runtime/split metadata differs from approved source/review")
            for token in tokens:
                if token.startswith("scene:") and token[6:] in manifest.get("locked_scene_ids", []):
                    raise PipelineError("locked scene group entered training")
                if token in token_splits and token_splits[token] != split:
                    raise PipelineError("feedback conversation/scene leaked between splits")
                if baseline_scene_splits.get(token, {split}) != {split}:
                    raise PipelineError("feedback scene conflicts with approved baseline split")
                if manifest.get("group_token_splits", {}).get(token) != split:
                    raise PipelineError("feedback group split manifest mismatch")
                token_splits[token] = split
        pairs = [row for _, row in read_jsonl(dataset_dir / f"preferences_{split}.jsonl")]
        if len(pairs) != manifest.get("preference_counts", {}).get(split):
            raise PipelineError("preference export count mismatch")
        for pair in pairs:
            metadata = pair.get("metadata", {})
            feedback_id = metadata.get("provenance", {}).get("feedback_id")
            sample = exported_samples.get(feedback_id)
            if sample is None or metadata.get("source_split") != split or sample["metadata"].get("source_split") != split:
                raise PipelineError("preference pair lacks matching approved SFT provenance")
            candidate = by_id[feedback_id]
            target = _target(candidate, decisions[feedback_id])[0]
            rejected = next((item["response"] for item in candidate["candidates"] if item["candidate_id"] == metadata.get("rejected_candidate_id")), None)
            if pair.get("chosen") != canonical_json(target) or rejected is None or pair.get("rejected") != canonical_json(rejected) or _normalized(target) == _normalized(rejected):
                raise PipelineError("preference pair differs from approved supervision")
            expected_metadata = {**sample["metadata"], "rejected_candidate_id": metadata.get("rejected_candidate_id")}
            if pair.get("prompt") != sample["messages"][:-1] or metadata != expected_metadata:
                raise PipelineError("preference prompt/provenance differs from matching SFT sample")
    if sorted(seen_ids) != sorted(manifest.get("exported_feedback_ids", [])) or seen_ids != set(by_id):
        raise PipelineError("exported feedback inventory mismatch")
    quality = read_json(dataset_dir / "quality_report.json")
    if quality.get("status") != "pass" or manifest.get("quality_gate_status") != "pass":
        raise PipelineError("feedback dataset quality gates are not passing")
    return manifest, quality
