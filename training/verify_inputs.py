from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from training.common import (
    BUILD_ID,
    DATASET_ROOT,
    PROJECT_ROOT,
    REPORT_ROOT,
    ensure_output_dirs,
    path_is_within,
    read_delimited,
    read_json,
    read_jsonl,
    sha256_file,
    utc_now,
    write_json,
)


REQUIRED_PATHS = [
    "reports/go_no_go.json",
    "build_report.json",
    "dataset_card.md",
    "checksums.sha256",
    "exports/text_sft/jp_train.jsonl",
    "exports/text_sft/jp_validation.jsonl",
    "exports/text_sft/zh_train.jsonl",
    "exports/text_sft/zh_validation.jsonl",
    "exports/eval/cases_jp.jsonl",
    "exports/eval/cases_zh.jsonl",
    "exports/tts/manifest.tsv",
    "exports/tts/filelist_train.txt",
    "exports/rag/chunks_train.jsonl",
]


def parse_checksum_manifest(path: Path) -> list[tuple[str, Path, str]]:
    entries: list[tuple[str, Path, str]] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        if not raw.strip():
            continue
        parts = raw.split(maxsplit=1)
        if len(parts) != 2 or len(parts[0]) != 64:
            raise ValueError(f"invalid checksum line {line_number}")
        relative = parts[1].lstrip("* ").replace("/", "\\")
        candidate = DATASET_ROOT / relative
        if not path_is_within(candidate, DATASET_ROOT):
            raise ValueError(f"checksum path escapes dataset root: {relative}")
        entries.append((parts[0].lower(), candidate, relative.replace("\\", "/")))
    return entries


def metadata_scene(row: dict[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    return str(row.get("scene_id") or metadata.get("scene_id") or "")


def metadata_build(row: dict[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    return str(row.get("build_id") or metadata.get("build_id") or "")


def collect_jsonl(path: Path) -> dict[str, Any]:
    rows = read_jsonl(path)
    scenes = {metadata_scene(row) for row in rows if metadata_scene(row)}
    builds = Counter(metadata_build(row) for row in rows if metadata_build(row))
    sample_ids = [
        str(row.get("sample_id") or row.get("chunk_id") or row.get("style_scene_id") or "")
        for row in rows
    ]
    duplicate_ids = sorted(key for key, count in Counter(sample_ids).items() if key and count > 1)
    return {
        "rows": len(rows),
        "scenes": scenes,
        "build_ids": dict(builds),
        "duplicate_ids": duplicate_ids,
    }


def verify() -> tuple[dict[str, Any], bool]:
    ensure_output_dirs()
    failures: list[str] = []
    notes: list[str] = []

    required = {relative: (DATASET_ROOT / relative).is_file() for relative in REQUIRED_PATHS}
    missing_required = [relative for relative, exists in required.items() if not exists]
    if missing_required:
        failures.append(f"missing required files: {missing_required}")

    go_report = read_json(DATASET_ROOT / "reports" / "go_no_go.json")
    build_report = read_json(DATASET_ROOT / "build_report.json")
    build_ids = {
        "go_no_go": go_report.get("build_id"),
        "build_report": build_report.get("build_id"),
    }
    build_id_ok = all(value == BUILD_ID for value in build_ids.values())
    if not build_id_ok:
        failures.append(f"build ID mismatch: {build_ids}")
    if go_report.get("decision") != "GO" or build_report.get("decision") != "GO":
        failures.append("formal data warehouse is not GO")

    checksum_entries = parse_checksum_manifest(DATASET_ROOT / "checksums.sha256")
    checksum_missing = [relative for _, path, relative in checksum_entries if not path.is_file()]
    existing = [(expected, path, relative) for expected, path, relative in checksum_entries if path.is_file()]
    with ThreadPoolExecutor(max_workers=8) as pool:
        actual_hashes = list(pool.map(lambda item: sha256_file(item[1]), existing))
    checksum_mismatches = [
        {"path": relative, "expected": expected, "actual": actual}
        for (expected, _, relative), actual in zip(existing, actual_hashes)
        if expected != actual
    ]
    checksums_ok = not checksum_missing and not checksum_mismatches
    if not checksums_ok:
        failures.append(
            f"checksum verification failed: missing={len(checksum_missing)}, mismatches={len(checksum_mismatches)}"
        )

    jsonl_specs = {
        "jp_train": "exports/text_sft/jp_train.jsonl",
        "jp_validation": "exports/text_sft/jp_validation.jsonl",
        "jp_test": "exports/text_sft/jp_test.jsonl",
        "zh_train": "exports/text_sft/zh_train.jsonl",
        "zh_validation": "exports/text_sft/zh_validation.jsonl",
        "zh_test": "exports/text_sft/zh_test.jsonl",
        "eval_jp": "exports/eval/cases_jp.jsonl",
        "eval_zh": "exports/eval/cases_zh.jsonl",
        "rag_train": "exports/rag/chunks_train.jsonl",
        "rag_validation": "exports/rag/chunks_validation.jsonl",
        "rag_test": "exports/rag/chunks_test.jsonl",
    }
    jsonl_results = {
        name: collect_jsonl(DATASET_ROOT / relative) for name, relative in jsonl_specs.items()
    }
    wrong_jsonl_builds = {
        name: result["build_ids"]
        for name, result in jsonl_results.items()
        if set(result["build_ids"]) != {BUILD_ID}
    }
    if wrong_jsonl_builds:
        failures.append(f"JSONL build ID mismatch: {wrong_jsonl_builds}")
    duplicate_jsonl_ids = {
        name: result["duplicate_ids"] for name, result in jsonl_results.items() if result["duplicate_ids"]
    }
    if duplicate_jsonl_ids:
        failures.append(f"duplicate JSONL sample IDs: {duplicate_jsonl_ids}")

    split_scenes: dict[str, set[str]] = defaultdict(set)
    for name, result in jsonl_results.items():
        if name.endswith("_train") or name == "rag_train":
            split_scenes["train"].update(result["scenes"])
        elif name.endswith("_validation") or name == "rag_validation":
            split_scenes["validation"].update(result["scenes"])
        elif name.endswith("_test") or name.startswith("eval_") or name == "rag_test":
            split_scenes["test"].update(result["scenes"])

    tts_rows = read_delimited(DATASET_ROOT / "exports" / "tts" / "manifest.tsv", delimiter="\t")
    tts_split_scenes: dict[str, set[str]] = defaultdict(set)
    tts_split_hashes: dict[str, set[str]] = defaultdict(set)
    missing_audio: list[str] = []
    blank_transcripts: list[str] = []
    utterance_ids: list[str] = []
    for row in tts_rows:
        split = row.get("split", "")
        tts_split_scenes[split].add(row.get("scene_id", ""))
        tts_split_hashes[split].add(row.get("voice_sha256", ""))
        utterance_ids.append(row.get("utterance_id", ""))
        if not row.get("text_jp", "").strip():
            blank_transcripts.append(row.get("utterance_id", ""))
        audio_path = Path(row.get("audio_path_absolute", ""))
        if not audio_path.is_file():
            missing_audio.append(str(audio_path))

    for split, scenes in tts_split_scenes.items():
        split_scenes[split].update(scene for scene in scenes if scene)

    split_pairs = [("train", "validation"), ("train", "test"), ("validation", "test")]
    scene_leakage = {
        f"{left}_vs_{right}": sorted(split_scenes[left] & split_scenes[right])
        for left, right in split_pairs
    }
    hash_leakage = {
        f"{left}_vs_{right}": sorted(tts_split_hashes[left] & tts_split_hashes[right] - {""})
        for left, right in split_pairs
    }
    if any(scene_leakage.values()):
        failures.append(f"scene split leakage: {scene_leakage}")
    if any(hash_leakage.values()):
        failures.append(f"audio hash split leakage: {hash_leakage}")

    duplicate_utterances = sorted(
        key for key, count in Counter(utterance_ids).items() if key and count > 1
    )
    if duplicate_utterances:
        failures.append(f"duplicate TTS utterance IDs: {duplicate_utterances[:20]}")
    if missing_audio:
        failures.append(f"missing TTS audio files: {len(missing_audio)}")
    if blank_transcripts:
        failures.append(f"blank TTS transcripts: {len(blank_transcripts)}")

    reference_selection = PROJECT_ROOT / "baselines" / "zero_shot" / "reference_selection.json"
    reference_check: dict[str, Any]
    if reference_selection.is_file():
        selection = read_json(reference_selection)
        selected = selection.get("references") or []
        bad = [row for row in selected if row.get("split") == "test"]
        reference_check = {"exists": True, "selected": len(selected), "test_references": bad}
        if bad:
            failures.append("test audio was selected as zero-shot reference")
    else:
        reference_check = {
            "exists": False,
            "selected": 0,
            "test_references": [],
            "note": "No reference has been selected before acoustic and manual review.",
        }
        notes.append("zero-shot reference selection intentionally deferred")

    test_isolation_ok = not scene_leakage["train_vs_test"] and not hash_leakage["train_vs_test"]
    result = {
        "build_id": BUILD_ID,
        "generated_utc": utc_now(),
        "decision": "GO" if not failures else "NO_GO",
        "formal_reports": {
            "go_no_go_decision": go_report.get("decision"),
            "build_report_decision": build_report.get("decision"),
            "build_ids": build_ids,
        },
        "required_files": required,
        "checksums": {
            "entry_count": len(checksum_entries),
            "verified": checksums_ok,
            "missing": checksum_missing,
            "mismatches": checksum_mismatches,
        },
        "jsonl": {
            name: {key: value for key, value in item.items() if key != "scenes"}
            for name, item in jsonl_results.items()
        },
        "tts": {
            "rows": len(tts_rows),
            "missing_audio": missing_audio,
            "blank_transcripts": blank_transcripts,
            "duplicate_utterance_ids": duplicate_utterances,
        },
        "split_checks": {
            "scene_counts": {key: len(value) for key, value in split_scenes.items()},
            "scene_leakage": scene_leakage,
            "audio_hash_leakage": hash_leakage,
            "test_isolation_ok": test_isolation_ok,
        },
        "reference_selection": reference_check,
        "failures": failures,
        "notes": notes,
    }
    return result, not failures


def write_report(result: dict[str, Any]) -> None:
    write_json(REPORT_ROOT / "training_input_verification.json", result)
    checks = result["checksums"]
    split = result["split_checks"]
    tts = result["tts"]
    lines = [
        "# Training Input Verification",
        "",
        f"- Build ID: `{result['build_id']}`",
        f"- Decision: **{result['decision']}**",
        f"- Generated UTC: `{result['generated_utc']}`",
        f"- Formal data warehouse modified: **No**",
        "",
        "## Gate Results",
        "",
        f"- Formal GO reports agree: `{result['formal_reports']['go_no_go_decision'] == 'GO' and result['formal_reports']['build_report_decision'] == 'GO'}`",
        f"- Checksum entries verified: `{checks['verified']}` ({checks['entry_count']} entries)",
        f"- Missing required files: `{sum(1 for exists in result['required_files'].values() if not exists)}`",
        f"- TTS manifest rows: `{tts['rows']}`",
        f"- Missing TTS audio: `{len(tts['missing_audio'])}`",
        f"- Empty transcripts: `{len(tts['blank_transcripts'])}`",
        f"- Duplicate utterance IDs: `{len(tts['duplicate_utterance_ids'])}`",
        f"- Scene leakage: `{sum(len(value) for value in split['scene_leakage'].values())}`",
        f"- Audio hash leakage: `{sum(len(value) for value in split['audio_hash_leakage'].values())}`",
        f"- Test isolated from training: `{split['test_isolation_ok']}`",
        f"- Test used as zero-shot reference: `{bool(result['reference_selection']['test_references'])}`",
        "",
        "## Test Set Policy",
        "",
        "Test cases and test audio remain excluded from training, reference selection and hyperparameter tuning. They are reserved for final blind evaluation.",
    ]
    if result["failures"]:
        lines.extend(["", "## Failures", ""] + [f"- {item}" for item in result["failures"]])
    if result["notes"]:
        lines.extend(["", "## Notes", ""] + [f"- {item}" for item in result["notes"]])
    (REPORT_ROOT / "training_input_verification.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify immutable Meguri training inputs")
    parser.parse_args()
    result, ok = verify()
    write_report(result)
    print(f"input verification: {result['decision']}")
    print(f"checksums: {result['checksums']['entry_count']} verified={result['checksums']['verified']}")
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
