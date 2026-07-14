from __future__ import annotations

import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from pydantic import ValidationError

from services.meguri_core.schemas import LlmResponse

from .common import (
    PipelineError,
    RUNTIME_CONFIG_ROOT,
    SOURCE_BUILD_ID,
    canonical_json,
    git_commit,
    read_json,
    read_jsonl,
    sha256_file,
    sha256_text,
    utc_now,
    write_json,
    write_jsonl,
)


CONVERTER_VERSION = "1.0.2"
EXPECTED_COUNTS = {
    "train": {"jp": 1313, "zh": 1313},
    "validation": {"jp": 283, "zh": 283},
    "locked_eval": {"jp": 92, "zh": 92},
}
SOURCE_FILES = {
    "train": {"jp": "jp_train.jsonl", "zh": "zh_train.jsonl"},
    "validation": {"jp": "jp_validation.jsonl", "zh": "zh_validation.jsonl"},
}
LANGUAGE_MAP = {"jp": "ja", "zh": "zh"}
MODE_BY_OUTFIT = {
    "01": "work",
    "02": "private",
    "03": "private",
    "04": "sleep",
    "05": "event",
    "06": "event",
    # These outfits are disabled at runtime but occur in the approved GO
    # exports. Retain them for fixed-count reproducibility while keeping outfit
    # eligibility outside the model.
    "07": "private",
    "08": "private",
}
VOICE_STYLE_NORMALIZATION = {
    # The approved exports predate the runtime voice-style enum and contain
    # this one legacy value. The response contract has no "embarrassed"
    # voice_style; restrained is the conservative delivery-only equivalent.
    "embarrassed": "restrained",
}


def _response_contract() -> tuple[dict[str, Any], str, list[str]]:
    schema_path = RUNTIME_CONFIG_ROOT / "meguri_response.schema.json"
    schema = read_json(schema_path)
    tags = list(schema["properties"]["expression_tag"]["enum"])
    return schema, sha256_file(schema_path), tags


def _system_prompt() -> tuple[str, str]:
    path = RUNTIME_CONFIG_ROOT / "meguri_system_prompt.txt"
    try:
        value = path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise PipelineError(f"cannot load system prompt: {path}") from exc
    if not value:
        raise PipelineError("system prompt is empty")
    return value, sha256_text(value)


def _runtime_block(metadata: dict[str, Any], allowed_tags: list[str]) -> tuple[str, str]:
    relationship = str(metadata.get("relationship_stage") or "")
    if relationship not in {"sibling", "pursuit", "lover"}:
        raise PipelineError(f"unsupported relationship_stage: {relationship!r}")
    outfit = str(metadata.get("outfit_code") or "")
    try:
        mode = MODE_BY_OUTFIT[outfit]
    except KeyError as exc:
        raise PipelineError(f"cannot derive interaction_mode from outfit_code: {outfit!r}") from exc
    block = (
        "<RUNTIME_STATE>\n"
        f"mode: {mode}\n"
        f"relationship_profile: {relationship}\n"
        f"outfit_code: {outfit}\n"
        f"allowed_expression_tags: {canonical_json(allowed_tags)}\n"
        "</RUNTIME_STATE>"
    )
    return block, mode


def _last_user_content(source: dict[str, Any]) -> str:
    messages = source.get("messages")
    if not isinstance(messages, list):
        raise PipelineError("source sample messages must be a list")
    users = [item for item in messages if isinstance(item, dict) and item.get("role") == "user"]
    if not users:
        raise PipelineError("source sample has no user message")
    content = users[-1].get("content")
    if not isinstance(content, str):
        raise PipelineError("source user content must be a string")
    return content


def _target_response(source: dict[str, Any]) -> tuple[dict[str, Any], dict[str, str]]:
    target = source.get("target")
    metadata = source.get("metadata")
    if not isinstance(target, dict) or not isinstance(metadata, dict):
        raise PipelineError("source target and metadata must be objects")
    source_voice_style = str(metadata.get("voice_style") or "")
    normalized_voice_style = VOICE_STYLE_NORMALIZATION.get(source_voice_style, source_voice_style)
    value = {
        "reply": target.get("reply"),
        "expression_tag": metadata.get("expression_tag"),
        "expression_intensity": metadata.get("expression_intensity"),
        "voice_style": normalized_voice_style,
        "memory_candidates": [],
    }
    try:
        validated = LlmResponse.model_validate(value)
    except ValidationError as exc:
        raise PipelineError(f"assistant target violates the runtime response contract: {exc}") from exc
    normalizations = {}
    if source_voice_style != normalized_voice_style:
        normalizations["voice_style"] = f"{source_voice_style}->{normalized_voice_style}"
    return validated.model_dump(mode="json"), normalizations


def convert_record(
    source: dict[str, Any],
    *,
    source_path: Path,
    source_ref: str,
    source_line_number: int,
    split: str,
    source_language: str,
    prompt: str,
    prompt_hash: str,
    schema_hash: str,
    allowed_tags: list[str],
) -> dict[str, Any]:
    if source.get("build_id") != SOURCE_BUILD_ID:
        raise PipelineError(
            f"source build ID mismatch at {source_path}:{source_line_number}: {source.get('build_id')!r}"
        )
    if source.get("language") != source_language:
        raise PipelineError(f"source language mismatch at {source_path}:{source_line_number}")
    metadata = source.get("metadata")
    if not isinstance(metadata, dict):
        raise PipelineError(f"source metadata missing at {source_path}:{source_line_number}")
    if metadata.get("split") != split:
        raise PipelineError(f"source split mismatch at {source_path}:{source_line_number}")
    runtime_block, interaction_mode = _runtime_block(metadata, allowed_tags)
    assistant, target_normalizations = _target_response(source)
    source_sample_id = str(source.get("sample_id") or "")
    if not source_sample_id:
        raise PipelineError(f"source sample_id missing at {source_path}:{source_line_number}")
    line_id = str(metadata.get("line_id") or "")
    scene_id = str(metadata.get("scene_id") or "")
    if not line_id or not scene_id:
        raise PipelineError(f"line_id/scene_id missing at {source_path}:{source_line_number}")
    provenance = {
        "source_export": source_ref,
        "source_export_line": source_line_number,
        "source_file": metadata.get("source_file"),
        "source_row_number": metadata.get("source_row_number"),
        "source_line_id": metadata.get("source_line_id"),
        "source_script": metadata.get("source_script"),
        "source_order": metadata.get("source_order"),
    }
    if any(value in (None, "") for value in provenance.values()):
        raise PipelineError(f"incomplete provenance at {source_path}:{source_line_number}")
    return {
        "messages": [
            {"role": "system", "content": prompt + "\n\n" + runtime_block},
            {"role": "user", "content": _last_user_content(source)},
            {"role": "assistant", "content": canonical_json(assistant)},
        ],
        "metadata": {
            "sample_id": source_sample_id,
            "language": LANGUAGE_MAP[source_language],
            "source_language": source_language,
            "scene_id": scene_id,
            "line_id": line_id,
            "relationship_stage": metadata.get("relationship_stage"),
            "interaction_mode": interaction_mode,
            "interaction_mode_source": "deterministic_outfit_map_v1",
            "outfit_code": metadata.get("outfit_code"),
            "data_build_id": SOURCE_BUILD_ID,
            "source_split": split,
            "prompt_sha256": prompt_hash,
            "response_schema_sha256": schema_hash,
            "converter_version": CONVERTER_VERSION,
            "target_normalizations": target_normalizations,
            "provenance": provenance,
        },
    }


def _normalized(value: str) -> str:
    return re.sub(r"\s+", "", value).casefold()


def _distribution(rows: Iterable[dict[str, Any]], field: str) -> dict[str, int]:
    counter = Counter(
        str(metadata.get(field))
        for row in rows
        if isinstance(row, dict) and isinstance((metadata := row.get("metadata")), dict)
    )
    return dict(sorted(counter.items()))


def _metadata(row: Any) -> dict[str, Any]:
    if not isinstance(row, dict):
        return {}
    value = row.get("metadata")
    return value if isinstance(value, dict) else {}


def _assistant_payload(row: Any) -> dict[str, Any] | None:
    try:
        messages = row["messages"]
        value = json.loads(messages[-1]["content"])
    except (KeyError, IndexError, TypeError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def audit_records(
    train_rows: list[dict[str, Any]],
    validation_rows: list[dict[str, Any]],
    *,
    locked_scene_ids: set[str],
    expected_counts: dict[str, dict[str, int]] = EXPECTED_COUNTS,
) -> dict[str, Any]:
    all_rows = train_rows + validation_rows
    parse_errors = 0
    schema_errors = 0
    extra_field_errors = 0
    invalid_enum_errors = 0
    provenance_missing = 0
    build_id_mixing = 0
    sample_ids: set[str] = set()
    duplicate_sample_ids = 0
    reply_groups: dict[tuple[str, str], list[str]] = defaultdict(list)
    empty_user_messages = 0
    memory_empty = 0
    for row in all_rows:
        assistant = _assistant_payload(row)
        if assistant is None:
            parse_errors += 1
            continue
        try:
            LlmResponse.model_validate(assistant)
        except ValidationError as exc:
            schema_errors += 1
            messages = str(exc)
            extra_field_errors += int("extra_forbidden" in messages)
            invalid_enum_errors += int("literal_error" in messages)
        metadata = _metadata(row)
        provenance = metadata.get("provenance")
        if not isinstance(provenance, dict) or any(value in (None, "") for value in provenance.values()):
            provenance_missing += 1
        if metadata.get("data_build_id") != SOURCE_BUILD_ID:
            build_id_mixing += 1
        sample_id = str(metadata.get("sample_id") or "")
        if sample_id in sample_ids:
            duplicate_sample_ids += 1
        sample_ids.add(sample_id)
        language = str(metadata.get("language"))
        reply_groups[(language, _normalized(str(assistant.get("reply") or "")))].append(sample_id)
        try:
            user_content = row["messages"][-2].get("content")
        except (KeyError, IndexError, TypeError, AttributeError):
            user_content = None
        if not str(user_content or ""):
            empty_user_messages += 1
        if assistant.get("memory_candidates") == []:
            memory_empty += 1

    train_scenes = {
        str(metadata["scene_id"])
        for row in train_rows
        if (metadata := _metadata(row)).get("scene_id")
    }
    validation_scenes = {
        str(metadata["scene_id"])
        for row in validation_rows
        if (metadata := _metadata(row)).get("scene_id")
    }
    scene_leaks = sorted(train_scenes & validation_scenes)
    locked_leaks = sorted((train_scenes | validation_scenes) & locked_scene_ids)
    actual_counts = {
        "train": dict(
            Counter(
                metadata["source_language"]
                for row in train_rows
                if (metadata := _metadata(row)).get("source_language")
            )
        ),
        "validation": dict(
            Counter(
                metadata["source_language"]
                for row in validation_rows
                if (metadata := _metadata(row)).get("source_language")
            )
        ),
    }
    count_mismatches: dict[str, dict[str, dict[str, int]]] = {}
    for split in ("train", "validation"):
        for language in ("jp", "zh"):
            expected = expected_counts[split][language]
            actual = int(actual_counts.get(split, {}).get(language, 0))
            if actual != expected:
                count_mismatches.setdefault(split, {})[language] = {"expected": expected, "actual": actual}
    duplicate_groups = [
        {
            "language": language,
            "normalized_reply_sha256": sha256_text(reply),
            "sample_ids": ids,
        }
        for (language, reply), ids in sorted(reply_groups.items())
        if reply and len(ids) > 1
    ]
    prompt_hashes = sorted(
        {
            str(metadata.get("prompt_sha256"))
            for row in all_rows
            if (metadata := _metadata(row)).get("prompt_sha256")
        }
    )
    blockers = {
        "json_parse_errors": parse_errors,
        "response_schema_errors": schema_errors,
        "extra_field_errors": extra_field_errors,
        "invalid_enum_errors": invalid_enum_errors,
        "scene_leakage": len(scene_leaks),
        "locked_eval_leakage": len(locked_leaks),
        "build_id_mixing": build_id_mixing,
        "provenance_missing": provenance_missing,
        "duplicate_sample_ids": duplicate_sample_ids,
        "count_mismatch_groups": sum(len(value) for value in count_mismatches.values()),
        "prompt_hash_count_not_one": 0 if len(prompt_hashes) == 1 else len(prompt_hashes),
    }
    return {
        "status": "pass" if all(value == 0 for value in blockers.values()) else "fail",
        "blockers": blockers,
        "counts": {
            "train": {"total": len(train_rows), **actual_counts.get("train", {})},
            "validation": {"total": len(validation_rows), **actual_counts.get("validation", {})},
            "locked_eval": {
                "total": expected_counts["locked_eval"]["jp"] + expected_counts["locked_eval"]["zh"],
                **expected_counts["locked_eval"],
                "source": "build_report.json only; locked case content was not read",
            },
        },
        "count_mismatches": count_mismatches,
        "scene_leakage": scene_leaks,
        "locked_eval_leakage": locked_leaks,
        "prompt_hashes": prompt_hashes,
        "ordinary_empty_memory_candidates": {
            "count": memory_empty,
            "ratio": round(memory_empty / len(all_rows), 6) if all_rows else 0.0,
        },
        "target_normalizations": dict(
            sorted(
                Counter(
                    normalization
                    for row in all_rows
                    for normalization in _metadata(row).get("target_normalizations", {}).values()
                ).items()
            )
        ),
        "empty_user_messages": empty_user_messages,
        "distributions": {
            "language": _distribution(all_rows, "language"),
            "relationship_stage": _distribution(all_rows, "relationship_stage"),
            "interaction_mode": _distribution(all_rows, "interaction_mode"),
            "expression_intensity": dict(
                sorted(
                    Counter(
                        assistant["expression_intensity"]
                        for row in all_rows
                        if (assistant := _assistant_payload(row)) is not None
                        and assistant.get("expression_intensity")
                    ).items()
                )
            ),
        },
        "paired_ratio": _paired_ratio(all_rows),
        "near_duplicate_and_memorization_risk": {
            "exact_normalized_reply_duplicate_groups": len(duplicate_groups),
            "exact_normalized_reply_duplicate_samples": sum(len(group["sample_ids"]) for group in duplicate_groups),
            "examples": duplicate_groups[:20],
            "risk_note": "Targets are source-work dialogue; exact/near-match checks remain mandatory during model evaluation.",
        },
    }


def _paired_ratio(rows: list[dict[str, Any]]) -> dict[str, Any]:
    by_line: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        metadata = _metadata(row)
        if metadata.get("line_id") and metadata.get("language"):
            by_line[str(metadata["line_id"])].add(str(metadata["language"]))
    paired = sum(1 for languages in by_line.values() if languages == {"ja", "zh"})
    return {
        "paired_line_ids": paired,
        "total_line_ids": len(by_line),
        "ratio": round(paired / len(by_line), 6) if by_line else 0.0,
    }


def load_locked_scene_ids(split_root: Path) -> set[str]:
    path = split_root / "test_scene_ids.txt"
    try:
        values = {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}
    except OSError as exc:
        raise PipelineError(f"cannot read locked split metadata: {path}") from exc
    if not values:
        raise PipelineError(f"locked split metadata is empty: {path}")
    return values


def build_dataset(
    *,
    data_root: Path,
    split_root: Path,
    output_root: Path,
    expected_counts: dict[str, dict[str, int]] = EXPECTED_COUNTS,
) -> Path:
    data_root = data_root.resolve()
    output_root = output_root.resolve()
    try:
        output_root.relative_to(data_root)
    except ValueError:
        pass
    else:
        raise PipelineError("derived dataset output must not be inside the read-only source data root")
    build_report = read_json(data_root / "build_report.json")
    if build_report.get("build_id") != SOURCE_BUILD_ID or build_report.get("decision") != "GO":
        raise PipelineError("source build report is not the approved GO build")
    locked_counts = build_report.get("counts", {}).get("text_sft", {})
    for language in ("jp", "zh"):
        if locked_counts.get(language, {}).get("test") != expected_counts["locked_eval"][language]:
            raise PipelineError(f"locked eval count mismatch in build report for {language}")

    prompt, prompt_hash = _system_prompt()
    _, schema_hash, allowed_tags = _response_contract()
    source_hashes: dict[str, str] = {}
    converted: dict[str, list[dict[str, Any]]] = {"train": [], "validation": []}
    for split in ("train", "validation"):
        for language in ("jp", "zh"):
            path = data_root / "exports" / "text_sft" / SOURCE_FILES[split][language]
            source_ref = f"exports/text_sft/{SOURCE_FILES[split][language]}"
            source_hashes[source_ref] = sha256_file(path)
            for line_number, source in read_jsonl(path):
                converted[split].append(
                    convert_record(
                        source,
                        source_path=path,
                        source_ref=source_ref,
                        source_line_number=line_number,
                        split=split,
                        source_language=language,
                        prompt=prompt,
                        prompt_hash=prompt_hash,
                        schema_hash=schema_hash,
                        allowed_tags=allowed_tags,
                    )
                )
    locked_scene_ids = load_locked_scene_ids(split_root)
    quality = audit_records(
        converted["train"],
        converted["validation"],
        locked_scene_ids=locked_scene_ids,
        expected_counts=expected_counts,
    )
    identity = {
        "source_build_id": SOURCE_BUILD_ID,
        "input_hashes": source_hashes,
        "prompt_sha256": prompt_hash,
        "response_schema_sha256": schema_hash,
        "converter_version": CONVERTER_VERSION,
        "normalization_policy": {
            "voice_style": VOICE_STYLE_NORMALIZATION,
            "reason": "map legacy export values into the pinned runtime response schema without mutating sources",
        },
    }
    dataset_id = "meguri-text-sft-v1-" + sha256_text(canonical_json(identity))[:12]
    output_dir = output_root / dataset_id
    if output_dir.exists():
        raise PipelineError(f"refusing to overwrite existing derived dataset: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=False)
    train_path = output_dir / "train.jsonl"
    validation_path = output_dir / "validation.jsonl"
    write_jsonl(train_path, converted["train"])
    write_jsonl(validation_path, converted["validation"])
    quality.update(
        {
            "schema_version": 1,
            "dataset_id": dataset_id,
            "source_build_id": SOURCE_BUILD_ID,
            "generated_at": utc_now(),
        }
    )
    write_json(output_dir / "quality_report.json", quality)
    manifest = {
        "schema_version": 1,
        "dataset_id": dataset_id,
        "source_build_id": SOURCE_BUILD_ID,
        "train_count": len(converted["train"]),
        "validation_count": len(converted["validation"]),
        "locked_eval_count": expected_counts["locked_eval"]["jp"] + expected_counts["locked_eval"]["zh"],
        "languages": ["ja", "zh"],
        "input_hashes": source_hashes,
        "split_metadata_sha256": sha256_file(split_root / "test_scene_ids.txt"),
        "conversion_commit": git_commit(),
        "converter_version": CONVERTER_VERSION,
        "normalization_policy": identity["normalization_policy"],
        "prompt_sha256": prompt_hash,
        "response_schema_sha256": schema_hash,
        "created_at": utc_now(),
        "files": {
            "train.jsonl": sha256_file(train_path),
            "validation.jsonl": sha256_file(validation_path),
            "quality_report.json": sha256_file(output_dir / "quality_report.json"),
        },
        "locked_eval_policy": {
            "content_read_by_converter": False,
            "used_for_training": False,
            "used_for_prompt_tuning": False,
            "count_source": "build_report.json",
        },
        "quality_gate_status": quality["status"],
    }
    write_json(output_dir / "dataset_manifest.json", manifest)
    if quality["status"] != "pass":
        raise PipelineError(f"derived dataset failed quality gates: {output_dir / 'quality_report.json'}")
    return output_dir


def validate_dataset(
    dataset_dir: Path,
    *,
    split_root: Path,
    expected_counts: dict[str, dict[str, int]] = EXPECTED_COUNTS,
) -> dict[str, Any]:
    dataset_dir = dataset_dir.resolve()
    manifest = read_json(dataset_dir / "dataset_manifest.json")
    train_rows = [row for _, row in read_jsonl(dataset_dir / "train.jsonl")]
    validation_rows = [row for _, row in read_jsonl(dataset_dir / "validation.jsonl")]
    quality = audit_records(
        train_rows,
        validation_rows,
        locked_scene_ids=load_locked_scene_ids(split_root),
        expected_counts=expected_counts,
    )
    file_hash_errors: dict[str, dict[str, str]] = {}
    for name in ("train.jsonl", "validation.jsonl", "quality_report.json"):
        expected = manifest.get("files", {}).get(name)
        actual = sha256_file(dataset_dir / name)
        if expected != actual:
            file_hash_errors[name] = {"expected": str(expected), "actual": actual}
    if manifest.get("source_build_id") != SOURCE_BUILD_ID:
        quality["blockers"]["manifest_build_id_mismatch"] = 1
    if manifest.get("quality_gate_status") != "pass":
        quality["blockers"]["manifest_quality_status_not_pass"] = 1
    quality["manifest_file_hash_errors"] = file_hash_errors
    quality["status"] = (
        "pass"
        if all(value == 0 for value in quality["blockers"].values()) and not file_hash_errors
        else "fail"
    )
    return quality
