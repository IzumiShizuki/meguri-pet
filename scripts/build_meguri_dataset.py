from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import pyarrow as pa
import pyarrow.parquet as pq
from jsonschema import Draft202012Validator


PROJECT_ROOT = Path("D:/program/meguri-pet")
DATA_ROOT = PROJECT_ROOT / "data" / "meguri"
ALIGNED_ROOT = DATA_ROOT / "aligned_v1"
SOURCE_V2_ROOT = DATA_ROOT / "source_v2"
ASSET_ROOT = DATA_ROOT / "assets"
OUTPUT_ROOT = PROJECT_ROOT / "datasets" / "meguri"
FFMPEG = Path("D:/environment/ffmpeg/bin/ffmpeg.exe")
FFPROBE = Path("D:/environment/ffmpeg/bin/ffprobe.exe")

PROJECT_ID = "meguri_ai"
CHARACTER_ID = "meguri"
CHARACTER_CODE = "e"
DISPLAY_NAME_ZH = "爱莉"
SPEAKER_JP = "メグリ"
KNOWN_RELATIONSHIPS = {"sibling", "pursuit", "lover"}
TEXT_EXTENSIONS = {".csv", ".tsv", ".json", ".jsonl", ".txt", ".md", ".yaml", ".yml", ".html"}
ZERO_WIDTH_RE = re.compile("[\u200b\u200c\u200d\ufeff]")
SPACE_RE = re.compile(r"[ \t\u3000]+")
SPRITE_RE = re.compile(r"^c(?P<char>[a-z])(?P<outfit>\d{2})(?P<expression>\d{3})(?P<size>[lm])$", re.I)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def boolish(value: Any) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "y"}


def intish(value: Any, default: int = 0) -> int:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def floatish(value: Any, default: float = 0.0) -> float:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return default


def normalize_text(value: Any, strip_outer_quotes: bool = False) -> str:
    text = unicodedata.normalize("NFC", str(value or ""))
    text = ZERO_WIDTH_RE.sub("", text).replace("\r\n", "\n").replace("\r", "\n")
    text = text.translate(str.maketrans({"\u00a0": " ", "\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"'}))
    lines = [SPACE_RE.sub(" ", line).strip() for line in text.split("\n")]
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    text = "\n".join(lines)
    if strip_outer_quotes and len(text) >= 2:
        for left, right in (("「", "」"), ("『", "』"), ('"', '"')):
            if text.startswith(left) and text.endswith(right):
                return text[len(left) : -len(right)].strip()
    return text


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stable_id(prefix: str, value: str, length: int = 20) -> str:
    return f"{prefix}_{hashlib.sha256(value.encode('utf-8')).hexdigest()[:length]}"


def project_rel(path: Path) -> str:
    return path.resolve().relative_to(PROJECT_ROOT.resolve()).as_posix()


def resolve_project_path(value: str) -> Path | None:
    value = normalize_text(value)
    if not value:
        return None
    candidate = Path(value)
    return candidate if candidate.is_absolute() else PROJECT_ROOT / candidate


def read_csv(path: Path, delimiter: str = ",") -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=delimiter))


def write_csv(path: Path, rows: Iterable[dict[str, Any]], fieldnames: list[str], delimiter: str = ",") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, delimiter=delimiter, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            count += 1
    return count


def write_parquet(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    table = pa.Table.from_pylist(rows)
    pq.write_table(table, path, compression="zstd", use_dictionary=True)


def inventory_one(path: Path) -> dict[str, Any]:
    digest = hashlib.sha256()
    line_count = 0
    count_lines = path.suffix.lower() in TEXT_EXTENSIONS
    last_byte = b""
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
            if count_lines:
                line_count += chunk.count(b"\n")
                last_byte = chunk[-1:]
    if count_lines and path.stat().st_size and last_byte != b"\n":
        line_count += 1
    stat = path.stat()
    return {
        "path": project_rel(path),
        "bytes": stat.st_size,
        "modified_utc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(timespec="seconds"),
        "line_count": line_count if count_lines else None,
        "sha256": digest.hexdigest(),
    }


def build_inventory() -> list[dict[str, Any]]:
    files = sorted(path for path in DATA_ROOT.rglob("*") if path.is_file())
    rows: list[dict[str, Any]] = []
    workers = min(12, max(4, (os.cpu_count() or 4)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(inventory_one, path): path for path in files}
        for future in as_completed(futures):
            rows.append(future.result())
    return sorted(rows, key=lambda row: row["path"].lower())


def schema_observation(path: Path, delimiter: str = ",") -> dict[str, Any]:
    rows = read_csv(path, delimiter)
    fields = list(rows[0].keys()) if rows else []
    return {
        "path": project_rel(path),
        "row_count": len(rows),
        "fields": [
            {
                "name": field,
                "non_empty": sum(1 for row in rows if normalize_text(row.get(field, ""))),
                "examples": [normalize_text(row.get(field, ""))[:100] for row in rows if normalize_text(row.get(field, ""))][:3],
            }
            for field in fields
        ],
    }


def copy_source_snapshots() -> None:
    aligned_target = OUTPUT_ROOT / "source" / "aligned_v1"
    original_target = OUTPUT_ROOT / "source" / "original_manifests"
    shutil.copytree(ALIGNED_ROOT, aligned_target, dirs_exist_ok=True)
    shutil.copytree(SOURCE_V2_ROOT, original_target, dirs_exist_ok=True)


def find_viewable_sprite_root() -> Path:
    for game_dir in Path("D:/G").iterdir():
        candidate = game_dir / "_extract_moteyaba" / "deliverables_viewable" / "sprite_composited"
        if candidate.is_dir():
            return candidate
    raise FileNotFoundError("Could not locate deliverables_viewable/sprite_composited under D:/G")


def canonical_split(value: str) -> str:
    value = normalize_text(value).lower()
    return "validation" if value == "dev" else value


def sprite_id_from_file(value: str) -> str:
    stem = Path(normalize_text(value)).stem.lower()
    return f"spr_{stem}" if stem else ""


def make_dialogues(source_rows: list[dict[str, str]], build_id: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    source_file = project_rel(ALIGNED_ROOT / "manifests" / "dialogue_master.csv")
    for source_row_number, row in enumerate(source_rows, start=2):
        voice_sha = normalize_text(row.get("voice_sha256", "")).lower()
        source_voice_value = normalize_text(row.get("project_voice_path", "") or row.get("voice_path", ""))
        source_voice_path = resolve_project_path(source_voice_value)
        voice_name = Path(source_voice_value).name if source_voice_value else ""
        project_voice_path = ASSET_ROOT / "voice_safe" / voice_name if voice_name else None
        voice_path = project_voice_path if project_voice_path and project_voice_path.is_file() else source_voice_path
        exact_sprite = normalize_text(row.get("exact_sprite_file", ""))
        runtime_sprite = normalize_text(row.get("sprite_file", ""))
        exact_project_candidate = resolve_project_path(row.get("exact_project_sprite_path", ""))
        runtime_project_candidate = resolve_project_path(row.get("project_sprite_path", ""))
        if exact_project_candidate and exact_project_candidate.is_file():
            chosen_sprite = exact_sprite
            project_sprite = exact_project_candidate
            chosen_sprite_source = "exact"
        elif runtime_project_candidate and runtime_project_candidate.is_file():
            chosen_sprite = runtime_sprite
            project_sprite = runtime_project_candidate
            chosen_sprite_source = "runtime_fallback"
        else:
            chosen_sprite = exact_sprite or runtime_sprite
            project_sprite = exact_project_candidate or runtime_project_candidate
            chosen_sprite_source = normalize_text(row.get("sprite_match_source", "")) or "missing"
        project_sprite_value = (
            project_rel(project_sprite)
            if project_sprite and project_sprite.exists()
            else normalize_text(row.get("exact_project_sprite_path", "") or row.get("project_sprite_path", ""))
        )
        context_raw = normalize_text(row.get("context_json", ""))
        try:
            context = json.loads(context_raw) if context_raw else []
        except json.JSONDecodeError:
            context = []
        normalized_context = [
            {
                "speaker_jp": normalize_text(item.get("speaker_jp", "")),
                "speaker_zh": normalize_text(item.get("speaker_zh", "")),
                "text_jp": normalize_text(item.get("text_jp", ""), strip_outer_quotes=True),
                "text_zh": normalize_text(item.get("text_zh", ""), strip_outer_quotes=True),
            }
            for item in context
            if isinstance(item, dict)
        ]
        line_id = normalize_text(row.get("line_id", ""))
        rows.append(
            {
                "build_id": build_id,
                "project_id": PROJECT_ID,
                "character_id": CHARACTER_ID,
                "character_code": CHARACTER_CODE,
                "line_id": line_id,
                "scene_id": normalize_text(row.get("scene_id", "")),
                "scene_uid": stable_id("scene", normalize_text(row.get("scene_id", ""))),
                "source_script": normalize_text(row.get("source_script", "")),
                "source_order": intish(row.get("source_order")),
                "scene_title_jp": normalize_text(row.get("scene_title_jp", "")),
                "scene_title_zh": normalize_text(row.get("scene_title_zh", "")),
                "speaker_id": normalize_text(row.get("speaker_id", "")),
                "speaker_jp": normalize_text(row.get("speaker_raw_jp", "")),
                "speaker_zh": normalize_text(row.get("speaker_raw_zh", "")),
                "text_jp_raw": normalize_text(row.get("text_jp_raw", "")),
                "text_zh_raw": normalize_text(row.get("text_zh_raw", "")),
                "text_jp_normalized": normalize_text(row.get("text_jp", "") or row.get("text_jp_raw", ""), strip_outer_quotes=True),
                "text_zh_normalized": normalize_text(row.get("text_zh", "") or row.get("text_zh_raw", ""), strip_outer_quotes=True),
                "context_json": json.dumps(normalized_context, ensure_ascii=False, separators=(",", ":")),
                "voice_id": normalize_text(row.get("voice_id", "")),
                "voice_sha256": voice_sha,
                "utterance_id": stable_id("utt", voice_sha) if voice_sha else "",
                "voice_project_path": project_rel(project_voice_path) if project_voice_path and project_voice_path.is_file() else normalize_text(row.get("project_voice_path", "")),
                "voice_exists": bool(voice_path and voice_path.is_file()),
                "voice_style": normalize_text(row.get("voice_style", "")) or "neutral",
                "sprite_file": chosen_sprite,
                "sprite_id": sprite_id_from_file(chosen_sprite),
                "sprite_project_path": project_rel(project_sprite) if project_sprite and project_sprite.exists() else normalize_text(project_sprite_value),
                "sprite_exists": bool(project_sprite and project_sprite.is_file()),
                "sprite_match_source": chosen_sprite_source,
                "sprite_scope": "meguri" if project_sprite and project_sprite.is_relative_to(ASSET_ROOT / "sprites" / "meguri") else "unresolved",
                "runtime_sprite_file": runtime_sprite,
                "runtime_sprite_id": sprite_id_from_file(runtime_sprite),
                "outfit_code": normalize_text(row.get("exact_outfit_code", "") or row.get("outfit_code", "")),
                "expression_code": normalize_text(row.get("exact_expression_code", "") or row.get("expression_code", "")),
                "expression_tag": normalize_text(row.get("expression_tag", "")) or "neutral",
                "expression_intensity": normalize_text(row.get("expression_intensity", "")) or "low",
                "content_rating": normalize_text(row.get("content_rating", "")),
                "content_reason": normalize_text(row.get("content_reason", "")),
                "is_h_scene": boolish(row.get("is_h_scene")),
                "text_train_allowed": boolish(row.get("text_train_allowed")),
                "voice_train_allowed": boolish(row.get("voice_train_allowed")),
                "relationship_stage": normalize_text(row.get("relationship_stage", "")) or "unknown",
                "relationship_stage_source": normalize_text(row.get("relationship_stage_source", "")),
                "relationship_stage_confidence": floatish(row.get("relationship_stage_confidence")),
                "split": canonical_split(row.get("split", "")),
                "source_file": source_file,
                "source_row_number": source_row_number,
                "source_line_id": line_id,
                "source_provenance": normalize_text(row.get("provenance", "")),
            }
        )
    return rows


def make_scenes(dialogues: list[dict[str, Any]], build_id: str) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in dialogues:
        grouped[row["scene_id"]].append(row)
    scenes: list[dict[str, Any]] = []
    for scene_id, rows in sorted(grouped.items()):
        splits = sorted({row["split"] for row in rows})
        ratings = Counter(row["content_rating"] for row in rows)
        relationships = Counter(row["relationship_stage"] for row in rows)
        scenes.append(
            {
                "build_id": build_id,
                "scene_id": scene_id,
                "scene_uid": stable_id("scene", scene_id),
                "source_script": rows[0]["source_script"],
                "scene_title_jp": rows[0]["scene_title_jp"],
                "scene_title_zh": rows[0]["scene_title_zh"],
                "split": splits[0] if len(splits) == 1 else "conflict",
                "line_count": len(rows),
                "safe_line_count": ratings.get("safe", 0),
                "suggestive_line_count": ratings.get("suggestive", 0),
                "explicit_line_count": ratings.get("explicit", 0),
                "text_train_candidate_count": sum(1 for row in rows if row["text_train_allowed"]),
                "voice_train_candidate_count": sum(1 for row in rows if row["voice_train_allowed"]),
                "relationship_stage": relationships.most_common(1)[0][0],
                "relationship_counts_json": json.dumps(relationships, ensure_ascii=False, sort_keys=True),
                "line_ids_json": json.dumps([row["line_id"] for row in rows], ensure_ascii=False),
                "source_file": rows[0]["source_file"],
            }
        )
    return scenes


def probe_audio(path: Path) -> dict[str, Any]:
    result = {
        "path": project_rel(path) if path.exists() else str(path),
        "exists": path.is_file(),
        "duration_seconds": 0.0,
        "codec": "",
        "sample_rate": 0,
        "channels": 0,
        "probe_ok": False,
        "decode_ok": False,
        "error": "",
    }
    if not path.is_file():
        result["error"] = "missing_file"
        return result
    try:
        probe = subprocess.run(
            [str(FFPROBE), "-v", "error", "-show_entries", "format=duration:stream=codec_name,sample_rate,channels", "-of", "json", str(path)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=False,
        )
        payload = json.loads(probe.stdout or "{}") if probe.returncode == 0 else {}
        stream = (payload.get("streams") or [{}])[0]
        result.update(
            {
                "duration_seconds": round(float((payload.get("format") or {}).get("duration") or 0), 6),
                "codec": str(stream.get("codec_name") or ""),
                "sample_rate": int(stream.get("sample_rate") or 0),
                "channels": int(stream.get("channels") or 0),
                "probe_ok": probe.returncode == 0 and bool(stream.get("codec_name")),
            }
        )
        decode = subprocess.run(
            [str(FFMPEG), "-v", "error", "-xerror", "-nostdin", "-i", str(path), "-f", "null", "-"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=60,
            check=False,
        )
        result["decode_ok"] = decode.returncode == 0
        if not result["decode_ok"]:
            result["error"] = (decode.stderr or "decode_failed")[-500:]
        elif not result["probe_ok"]:
            result["error"] = (probe.stderr or "probe_failed")[-500:]
    except (subprocess.TimeoutExpired, json.JSONDecodeError, OSError, ValueError) as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"[:500]
    return result


def validate_audio(paths: list[Path]) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    workers = min(10, max(4, (os.cpu_count() or 4) // 2))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(probe_audio, path): path for path in paths}
        for future in as_completed(futures):
            results.append(future.result())
    return sorted(results, key=lambda row: row["path"].lower())


def make_voices(
    dialogues: list[dict[str, Any]],
    voice_safe_rows: list[dict[str, str]],
    audio_validation: list[dict[str, Any]],
    build_id: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    validation_by_path = {row["path"]: row for row in audio_validation}
    all_by_hash: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in dialogues:
        key = row["voice_sha256"] or row["voice_project_path"]
        if key:
            all_by_hash[key].append(row)

    canonical_voices: list[dict[str, Any]] = []
    for key, rows in sorted(all_by_hash.items()):
        hashes = sorted({row["voice_sha256"] for row in rows if row["voice_sha256"]})
        paths = sorted({row["voice_project_path"] for row in rows if row["voice_project_path"]})
        texts_jp = sorted({row["text_jp_normalized"] for row in rows if row["text_jp_normalized"]})
        texts_zh = sorted({row["text_zh_normalized"] for row in rows if row["text_zh_normalized"]})
        validation = validation_by_path.get(paths[0], {}) if paths else {}
        canonical_voices.append(
            {
                "build_id": build_id,
                "utterance_id": stable_id("utt", hashes[0] if hashes else key),
                "voice_sha256": hashes[0] if hashes else "",
                "voice_ids_json": json.dumps(sorted({row["voice_id"] for row in rows if row["voice_id"]}), ensure_ascii=False),
                "project_paths_json": json.dumps(paths, ensure_ascii=False),
                "primary_project_path": paths[0] if paths else "",
                "text_jp_variants_json": json.dumps(texts_jp, ensure_ascii=False),
                "text_zh_variants_json": json.dumps(texts_zh, ensure_ascii=False),
                "line_ids_json": json.dumps([row["line_id"] for row in rows], ensure_ascii=False),
                "occurrence_count": len(rows),
                "duration_seconds": float(validation.get("duration_seconds") or 0.0),
                "codec": validation.get("codec", ""),
                "sample_rate": int(validation.get("sample_rate") or 0),
                "channels": int(validation.get("channels") or 0),
                "decode_ok_if_validated": bool(validation.get("decode_ok")),
                "source_file": rows[0]["source_file"],
            }
        )

    safe_enriched: list[dict[str, Any]] = []
    source_file = project_rel(ALIGNED_ROOT / "manifests" / "voice_train_safe.tsv")
    for source_row_number, row in enumerate(voice_safe_rows, start=2):
        project_path = resolve_project_path(row.get("project_voice_path", ""))
        project_path_value = project_rel(project_path) if project_path and project_path.exists() else normalize_text(row.get("project_voice_path", ""))
        validation = validation_by_path.get(project_path_value, {})
        voice_sha = normalize_text(row.get("voice_sha256", "")).lower()
        safe_enriched.append(
            {
                "build_id": build_id,
                "utterance_id": stable_id("utt", voice_sha) if voice_sha else stable_id("utt", project_path_value),
                "voice_id": normalize_text(row.get("voice_id", "")),
                "voice_sha256": voice_sha,
                "audio_path": project_path_value,
                "audio_path_absolute": str(project_path) if project_path else "",
                "text_jp": normalize_text(row.get("text_jp", ""), strip_outer_quotes=True),
                "speaker_id": normalize_text(row.get("speaker_id", "")),
                "line_id": normalize_text(row.get("line_id", "")),
                "scene_id": normalize_text(row.get("scene_id", "")),
                "source_script": normalize_text(row.get("source_script", "")),
                "content_rating": normalize_text(row.get("content_rating", "")),
                "voice_train_allowed": boolish(row.get("voice_train_allowed")),
                "relationship_stage": normalize_text(row.get("relationship_stage", "")),
                "outfit_code": normalize_text(row.get("outfit_code", "")),
                "expression_code": normalize_text(row.get("expression_code", "")),
                "voice_style": normalize_text(row.get("voice_style", "")) or "neutral",
                "split": canonical_split(row.get("split", "")),
                "exists": bool(validation.get("exists")),
                "decode_ok": bool(validation.get("decode_ok")),
                "duration_seconds": float(validation.get("duration_seconds") or 0.0),
                "codec": validation.get("codec", ""),
                "sample_rate": int(validation.get("sample_rate") or 0),
                "channels": int(validation.get("channels") or 0),
                "source_file": source_file,
                "source_row_number": source_row_number,
            }
        )

    hash_groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    voice_id_hashes: dict[str, set[str]] = defaultdict(set)
    for row in safe_enriched:
        hash_groups[row["voice_sha256"]].append(row)
        voice_id_hashes[row["voice_id"]].add(row["voice_sha256"])
    duplicate_groups = []
    text_conflicts = []
    for sha, rows in sorted(hash_groups.items()):
        if not sha:
            continue
        texts = sorted({row["text_jp"] for row in rows})
        if len(rows) > 1:
            duplicate_groups.append(
                {
                    "voice_sha256": sha,
                    "occurrences": len(rows),
                    "voice_ids": sorted({row["voice_id"] for row in rows}),
                    "line_ids": sorted({row["line_id"] for row in rows}),
                    "texts_jp": texts,
                }
            )
        if len(texts) > 1:
            text_conflicts.append({"voice_sha256": sha, "texts_jp": texts, "line_ids": [row["line_id"] for row in rows]})
    id_conflicts = [
        {"voice_id": voice_id, "hashes": sorted(hashes)}
        for voice_id, hashes in sorted(voice_id_hashes.items())
        if voice_id and len({value for value in hashes if value}) > 1
    ]
    report = {
        "safe_manifest_rows": len(safe_enriched),
        "unique_audio_hashes": len({row["voice_sha256"] for row in safe_enriched if row["voice_sha256"]}),
        "duplicate_hash_groups": duplicate_groups,
        "one_audio_multiple_text_conflicts": text_conflicts,
        "one_voice_id_multiple_hash_conflicts": id_conflicts,
    }
    return canonical_voices, safe_enriched, report


def make_sprites(source_rows: list[dict[str, str]], build_id: str) -> list[dict[str, Any]]:
    source_file = project_rel(ALIGNED_ROOT / "catalogs" / "sprite_catalog_meguri.csv")
    rows: list[dict[str, Any]] = []
    for source_row_number, row in enumerate(source_rows, start=2):
        sprite_file = normalize_text(row.get("sprite_file", ""))
        path = resolve_project_path(row.get("project_sprite_path", ""))
        stem = Path(sprite_file).stem
        match = SPRITE_RE.match(stem)
        rows.append(
            {
                "build_id": build_id,
                "sprite_id": sprite_id_from_file(sprite_file),
                "sprite_file": sprite_file,
                "sprite_code": normalize_text(row.get("sprite_code", "")) or stem,
                "character_code": match.group("char").lower() if match else normalize_text(row.get("character_code", "")),
                "outfit_code": match.group("outfit") if match else normalize_text(row.get("outfit_code", "")),
                "outfit_label": normalize_text(row.get("outfit_label", "")),
                "expression_code": match.group("expression") if match else normalize_text(row.get("expression_code", "")),
                "size": match.group("size").lower() if match else normalize_text(row.get("size", "")),
                "width": intish(row.get("width")),
                "height": intish(row.get("height")),
                "file_sha256": normalize_text(row.get("file_sha256", "")).lower(),
                "project_path": project_rel(path) if path and path.exists() else normalize_text(row.get("project_sprite_path", "")),
                "asset_exists": bool(path and path.is_file()),
                "expression_tag": normalize_text(row.get("expression_tag", "")) or "neutral",
                "expression_intensity": normalize_text(row.get("expression_intensity", "")) or "low",
                "excluded_default": boolish(row.get("excluded_default")),
                "label_status": normalize_text(row.get("label_status", "")),
                "usage_count": intish(row.get("usage_count")),
                "exact_usage_count": intish(row.get("exact_usage_count")),
                "source_file": source_file,
                "source_row_number": source_row_number,
            }
        )
    return rows


def make_links(dialogues: list[dict[str, Any]], build_id: str) -> list[dict[str, Any]]:
    return [
        {
            "build_id": build_id,
            "line_id": row["line_id"],
            "scene_id": row["scene_id"],
            "utterance_id": row["utterance_id"],
            "sprite_id": row["sprite_id"],
            "runtime_sprite_id": row["runtime_sprite_id"],
            "sprite_scope": row["sprite_scope"],
            "voice_exists": row["voice_exists"],
            "sprite_exists": row["sprite_exists"],
            "sprite_match_source": row["sprite_match_source"],
            "source_file": row["source_file"],
            "source_row_number": row["source_row_number"],
        }
        for row in dialogues
    ]


def eligible_text(row: dict[str, Any]) -> bool:
    return (
        row["content_rating"] == "safe"
        and row["text_train_allowed"]
        and row["relationship_stage"] in KNOWN_RELATIONSHIPS
        and bool(row["text_jp_normalized"])
        and bool(row["text_zh_normalized"])
    )


def make_training_sample(row: dict[str, Any], language: str) -> dict[str, Any]:
    context = json.loads(row["context_json"] or "[]")
    speaker_key = "speaker_jp" if language == "jp" else "speaker_zh"
    text_key = "text_jp" if language == "jp" else "text_zh"
    reply_key = "text_jp_normalized" if language == "jp" else "text_zh_normalized"
    target_speaker = SPEAKER_JP if language == "jp" else DISPLAY_NAME_ZH
    context_rows = [{"speaker": item.get(speaker_key, ""), "text": item.get(text_key, "")} for item in context if item.get(text_key)]
    context_text = "\n".join(f"{item['speaker']}: {item['text']}" if item["speaker"] else item["text"] for item in context_rows)
    return {
        "sample_id": f"{row['line_id']}_{language}",
        "build_id": row["build_id"],
        "character_id": CHARACTER_ID,
        "language": language,
        "context": context_rows,
        "target": {"speaker": target_speaker, "reply": row[reply_key]},
        "messages": [
            {"role": "system", "content": f"You are {target_speaker}. Reply consistently with the character and current relationship stage."},
            {"role": "user", "content": context_text},
            {"role": "assistant", "content": row[reply_key]},
        ],
        "metadata": {
            "line_id": row["line_id"],
            "scene_id": row["scene_id"],
            "source_script": row["source_script"],
            "source_order": row["source_order"],
            "split": row["split"],
            "relationship_stage": row["relationship_stage"],
            "expression_tag": row["expression_tag"],
            "expression_intensity": row["expression_intensity"],
            "voice_style": row["voice_style"],
            "outfit_code": row["outfit_code"],
            "expression_code": row["expression_code"],
            "sprite_id": row["sprite_id"],
            "utterance_id": row["utterance_id"],
            "source_file": row["source_file"],
            "source_row_number": row["source_row_number"],
            "source_line_id": row["source_line_id"],
        },
    }


def chunk_rows(rows: list[dict[str, Any]], size: int = 8) -> Iterable[list[dict[str, Any]]]:
    for index in range(0, len(rows), size):
        yield rows[index : index + size]


def make_rag_chunks(dialogues: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in dialogues:
        if eligible_text(row):
            grouped[row["scene_id"]].append(row)
    chunks: list[dict[str, Any]] = []
    for scene_id, scene_rows in sorted(grouped.items()):
        scene_rows.sort(key=lambda row: row["source_order"])
        for index, rows in enumerate(chunk_rows(scene_rows), start=1):
            chunks.append(
                {
                    "chunk_id": f"{scene_id}_chunk_{index:03d}",
                    "build_id": rows[0]["build_id"],
                    "scene_id": scene_id,
                    "split": rows[0]["split"],
                    "relationship_stage": rows[0]["relationship_stage"],
                    "line_ids": [row["line_id"] for row in rows],
                    "text_jp": "\n".join(f"{SPEAKER_JP}: {row['text_jp_normalized']}" for row in rows),
                    "text_zh": "\n".join(f"{DISPLAY_NAME_ZH}: {row['text_zh_normalized']}" for row in rows),
                    "metadata": {
                        "source_script": rows[0]["source_script"],
                        "outfit_codes": sorted({row["outfit_code"] for row in rows if row["outfit_code"]}),
                        "expression_tags": sorted({row["expression_tag"] for row in rows}),
                        "source_file": rows[0]["source_file"],
                    },
                }
            )
    return chunks


def make_tts_export(safe_rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    candidates = [
        row
        for row in safe_rows
        if row["voice_train_allowed"]
        and row["content_rating"] == "safe"
        and row["relationship_stage"] in KNOWN_RELATIONSHIPS
        and row["exists"]
        and row["decode_ok"]
        and row["text_jp"]
    ]
    by_hash: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in candidates:
        by_hash[row["voice_sha256"]].append(row)
    unique_rows: list[dict[str, Any]] = []
    excluded_duplicates: list[dict[str, Any]] = []
    for voice_sha, rows in sorted(by_hash.items()):
        rows.sort(key=lambda row: (row["scene_id"], row["line_id"]))
        primary = dict(rows[0])
        primary["duplicate_source_line_ids"] = [row["line_id"] for row in rows[1:]]
        unique_rows.append(primary)
        for duplicate in rows[1:]:
            excluded_duplicates.append(
                {
                    "voice_sha256": voice_sha,
                    "kept_line_id": primary["line_id"],
                    "excluded_line_id": duplicate["line_id"],
                    "reason": "duplicate_audio_hash",
                }
            )
    return unique_rows, excluded_duplicates


def training_schema() -> dict[str, Any]:
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "required": ["sample_id", "build_id", "character_id", "language", "context", "target", "messages", "metadata"],
        "properties": {
            "sample_id": {"type": "string", "minLength": 1},
            "build_id": {"type": "string", "minLength": 1},
            "character_id": {"const": CHARACTER_ID},
            "language": {"enum": ["jp", "zh"]},
            "context": {"type": "array", "items": {"type": "object", "required": ["speaker", "text"]}},
            "target": {"type": "object", "required": ["speaker", "reply"], "properties": {"speaker": {"type": "string"}, "reply": {"type": "string", "minLength": 1}}},
            "messages": {"type": "array", "minItems": 3},
            "metadata": {"type": "object", "required": ["line_id", "scene_id", "split", "source_file", "source_row_number"]},
        },
    }


def write_schemas() -> dict[str, dict[str, Any]]:
    schemas = {
        "dialogue.schema.json": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["build_id", "line_id", "scene_id", "text_jp_raw", "text_jp_normalized", "split", "source_file", "source_row_number"],
            "properties": {"line_id": {"type": "string", "minLength": 1}, "scene_id": {"type": "string", "minLength": 1}, "split": {"enum": ["train", "validation", "test"]}},
        },
        "voice.schema.json": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["build_id", "utterance_id", "voice_sha256", "primary_project_path"],
            "properties": {"utterance_id": {"type": "string", "minLength": 1}, "voice_sha256": {"type": "string"}},
        },
        "sprite.schema.json": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["build_id", "sprite_id", "outfit_code", "expression_code", "project_path"],
            "properties": {"sprite_id": {"type": "string", "minLength": 1}, "outfit_code": {"type": "string"}, "expression_code": {"type": "string"}},
        },
        "training_sample.schema.json": training_schema(),
    }
    for filename, schema in schemas.items():
        write_json(OUTPUT_ROOT / "schemas" / filename, schema)
    return schemas


def write_knowledge(dialogues: list[dict[str, Any]], rag_chunks: list[dict[str, Any]]) -> dict[str, int]:
    persona_rules = [
        {"rule_id": "identity", "character_id": CHARACTER_ID, "text_zh": "中文显示名为爱莉，日文说话人名为メグリ。", "source": "notion:rules"},
        {"rule_id": "relationship", "character_id": CHARACTER_ID, "text_zh": "关系状态只能由 sibling、pursuit、lover 三个已确认阶段驱动。", "source": "notion:rules"},
        {"rule_id": "expression", "character_id": CHARACTER_ID, "text_zh": "模型输出有限 expression_tag，运行时映射到真实 PNG。", "source": "notion:rules"},
        {"rule_id": "asset_paths", "character_id": CHARACTER_ID, "text_zh": "模型不得自行构造立绘或语音路径。", "source": "notion:rules"},
        {"rule_id": "safety", "character_id": CHARACTER_ID, "text_zh": "明确 H、suggestive 和 unknown 数据不得进入正式训练或默认 RAG。", "source": "notion:rules"},
        {"rule_id": "runtime", "character_id": CHARACTER_ID, "text_zh": "运行时 JSON 至少包含 reply、expression_tag、expression_intensity、voice_style、memory_candidates。", "source": "notion:rules"},
    ]
    style_scenes = [
        {
            "style_scene_id": f"style_{chunk['chunk_id']}",
            "scene_id": chunk["scene_id"],
            "relationship_stage": chunk["relationship_stage"],
            "split": chunk["split"],
            "text_jp": chunk["text_jp"],
            "text_zh": chunk["text_zh"],
            "line_ids": chunk["line_ids"],
            "build_id": chunk["build_id"],
        }
        for chunk in rag_chunks
    ]
    expression_examples: list[dict[str, Any]] = []
    per_tag: Counter[str] = Counter()
    for row in dialogues:
        if not eligible_text(row) or per_tag[row["expression_tag"]] >= 25:
            continue
        expression_examples.append(
            {
                "example_id": f"expr_{row['line_id']}",
                "expression_tag": row["expression_tag"],
                "expression_intensity": row["expression_intensity"],
                "outfit_code": row["outfit_code"],
                "expression_code": row["expression_code"],
                "sprite_id": row["sprite_id"],
                "text_jp": row["text_jp_normalized"],
                "text_zh": row["text_zh_normalized"],
                "line_id": row["line_id"],
                "build_id": row["build_id"],
            }
        )
        per_tag[row["expression_tag"]] += 1
    return {
        "persona_rules": write_jsonl(OUTPUT_ROOT / "knowledge" / "persona_rules.jsonl", persona_rules),
        "style_scenes": write_jsonl(OUTPUT_ROOT / "knowledge" / "style_scenes.jsonl", style_scenes),
        "expression_examples": write_jsonl(OUTPUT_ROOT / "knowledge" / "expression_examples.jsonl", expression_examples),
    }


def verify_unique(rows: list[dict[str, Any]], key: str) -> tuple[bool, list[str]]:
    values = [str(row.get(key, "")) for row in rows]
    duplicates = sorted(value for value, count in Counter(values).items() if not value or count > 1)
    return not duplicates, duplicates


def write_checksums() -> tuple[int, bool]:
    checksum_path = OUTPUT_ROOT / "checksums.sha256"
    files = sorted(path for path in OUTPUT_ROOT.rglob("*") if path.is_file() and path != checksum_path)
    lines = [f"{sha256_file(path)}  {path.relative_to(OUTPUT_ROOT).as_posix()}" for path in files]
    checksum_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    verified = True
    for line in lines:
        expected, relative = line.split("  ", 1)
        if sha256_file(OUTPUT_ROOT / relative) != expected:
            verified = False
            break
    return len(lines), verified


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the canonical Meguri training data warehouse.")
    parser.add_argument("--skip-audio-decode", action="store_true", help="Development only; produces NO-GO for formal training.")
    args = parser.parse_args()

    required = [ALIGNED_ROOT, SOURCE_V2_ROOT, FFMPEG, FFPROBE]
    missing_required = [str(path) for path in required if not path.exists()]
    if missing_required:
        raise FileNotFoundError(f"Required inputs are missing: {missing_required}")

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    for relative in [
        "schemas", "source/aligned_v1", "source/original_manifests", "source/asset_indexes",
        "canonical", "knowledge", "exports/rag", "exports/text_sft", "exports/tts",
        "exports/expression_map", "exports/eval", "reports",
    ]:
        (OUTPUT_ROOT / relative).mkdir(parents=True, exist_ok=True)

    inventory = build_inventory()
    build_fingerprint = hashlib.sha256(
        "\n".join(f"{row['path']}|{row['bytes']}|{row['sha256']}" for row in inventory).encode("utf-8")
    ).hexdigest()
    build_id = f"meguri_v2_{build_fingerprint[:16]}"
    build_started = utc_now()

    write_csv(
        OUTPUT_ROOT / "reports" / "input_inventory.csv",
        inventory,
        ["path", "bytes", "modified_utc", "line_count", "sha256"],
    )
    write_json(OUTPUT_ROOT / "reports" / "input_inventory.json", {"build_id": build_id, "files": inventory})
    copy_source_snapshots()

    dialogue_path = ALIGNED_ROOT / "manifests" / "dialogue_master.csv"
    voice_path = ALIGNED_ROOT / "manifests" / "voice_train_safe.tsv"
    sprite_path = ALIGNED_ROOT / "catalogs" / "sprite_catalog_meguri.csv"
    dialogue_source = read_csv(dialogue_path)
    voice_source = read_csv(voice_path, "\t")
    sprite_source = read_csv(sprite_path)

    observations = [
        schema_observation(dialogue_path),
        schema_observation(voice_path, "\t"),
        schema_observation(sprite_path),
    ]
    schema_lines = ["# Schema Observation", "", f"Build ID: `{build_id}`", ""]
    for observation in observations:
        schema_lines.extend(
            [
                f"## {observation['path']}",
                "",
                f"- Rows: {observation['row_count']}",
                f"- Fields: {len(observation['fields'])}",
                "",
                "| Field | Non-empty | Examples |",
                "|---|---:|---|",
            ]
        )
        for field in observation["fields"]:
            examples = " / ".join(value.replace("|", "\\|").replace("\n", " ") for value in field["examples"])
            schema_lines.append(f"| {field['name']} | {field['non_empty']} | {examples} |")
        schema_lines.append("")
    (OUTPUT_ROOT / "reports" / "schema_observation.md").write_text("\n".join(schema_lines), encoding="utf-8")

    dialogues = make_dialogues(dialogue_source, build_id)
    external_sprite_root = OUTPUT_ROOT / "source" / "asset_indexes" / "sprites_external"
    external_sprite_root.mkdir(parents=True, exist_ok=True)
    external_sprite_rows: dict[str, dict[str, Any]] = {}
    viewable_sprite_root = find_viewable_sprite_root()
    for row in dialogues:
        if row["sprite_exists"] or not row["sprite_file"]:
            continue
        source_sprite = viewable_sprite_root / row["sprite_file"]
        if not source_sprite.is_file():
            continue
        target_sprite = external_sprite_root / source_sprite.name
        shutil.copy2(source_sprite, target_sprite)
        row["sprite_project_path"] = project_rel(target_sprite)
        row["sprite_exists"] = True
        row["sprite_scope"] = "external"
        row["sprite_match_source"] = "external_source"
        external_sprite_rows[row["sprite_id"]] = {
            "build_id": build_id,
            "sprite_id": row["sprite_id"],
            "sprite_file": row["sprite_file"],
            "project_path": project_rel(target_sprite),
            "source_path": str(source_sprite),
            "scope": "external",
            "source_line_ids_json": json.dumps([], ensure_ascii=False),
        }
    for external in external_sprite_rows.values():
        external["source_line_ids_json"] = json.dumps(
            [row["line_id"] for row in dialogues if row["sprite_id"] == external["sprite_id"]], ensure_ascii=False
        )
    scenes = make_scenes(dialogues, build_id)
    sprites = make_sprites(sprite_source, build_id)
    links = make_links(dialogues, build_id)

    safe_audio_paths = sorted(
        {
            path.resolve()
            for row in voice_source
            if (path := resolve_project_path(row.get("project_voice_path", ""))) is not None
        }
    )
    if args.skip_audio_decode:
        audio_validation = [
            {
                "path": project_rel(path) if path.exists() else str(path),
                "exists": path.is_file(),
                "duration_seconds": 0.0,
                "codec": "",
                "sample_rate": 0,
                "channels": 0,
                "probe_ok": False,
                "decode_ok": False,
                "error": "audio_decode_skipped",
            }
            for path in safe_audio_paths
        ]
    else:
        audio_validation = validate_audio(safe_audio_paths)
    write_csv(
        OUTPUT_ROOT / "reports" / "audio_validation.csv",
        audio_validation,
        ["path", "exists", "duration_seconds", "codec", "sample_rate", "channels", "probe_ok", "decode_ok", "error"],
    )

    voices, voice_safe_enriched, duplicate_report = make_voices(dialogues, voice_source, audio_validation, build_id)
    tts_rows, tts_duplicates = make_tts_export(voice_safe_enriched)

    write_parquet(OUTPUT_ROOT / "canonical" / "dialogues.parquet", dialogues)
    write_parquet(OUTPUT_ROOT / "canonical" / "scenes.parquet", scenes)
    write_parquet(OUTPUT_ROOT / "canonical" / "voices.parquet", voices)
    write_parquet(OUTPUT_ROOT / "canonical" / "sprites.parquet", sprites)
    write_parquet(OUTPUT_ROOT / "canonical" / "external_sprite_refs.parquet", list(external_sprite_rows.values()))
    write_parquet(OUTPUT_ROOT / "canonical" / "dialogue_asset_links.parquet", links)

    write_csv(
        OUTPUT_ROOT / "source" / "asset_indexes" / "voice_safe_index.csv",
        voice_safe_enriched,
        list(voice_safe_enriched[0].keys()),
    )
    write_csv(
        OUTPUT_ROOT / "source" / "asset_indexes" / "sprite_index.csv",
        sprites,
        list(sprites[0].keys()),
    )
    if external_sprite_rows:
        write_csv(
            OUTPUT_ROOT / "source" / "asset_indexes" / "external_sprite_refs.csv",
            list(external_sprite_rows.values()),
            list(next(iter(external_sprite_rows.values())).keys()),
        )
    for source_name in ["cg_png_manifest.csv", "text_readable_manifest.csv"]:
        shutil.copy2(ALIGNED_ROOT / "asset_references" / source_name, OUTPUT_ROOT / "source" / "asset_indexes" / source_name)

    schemas = write_schemas()
    rag_chunks = make_rag_chunks(dialogues)
    rag_counts: dict[str, int] = {}
    for split in ["train", "validation", "test"]:
        split_rows = [row for row in rag_chunks if row["split"] == split]
        rag_counts[split] = write_jsonl(OUTPUT_ROOT / "exports" / "rag" / f"chunks_{split}.jsonl", split_rows)

    training_rows = [row for row in dialogues if eligible_text(row)]
    validator = Draft202012Validator(schemas["training_sample.schema.json"])
    sft_counts: dict[str, dict[str, int]] = {"jp": {}, "zh": {}}
    schema_errors: list[dict[str, Any]] = []
    all_samples: dict[str, list[dict[str, Any]]] = {"jp": [], "zh": []}
    for language in ["jp", "zh"]:
        for row in training_rows:
            sample = make_training_sample(row, language)
            errors = list(validator.iter_errors(sample))
            if errors:
                schema_errors.append({"sample_id": sample["sample_id"], "errors": [error.message for error in errors]})
            all_samples[language].append(sample)
        for split in ["train", "validation", "test"]:
            split_samples = [sample for sample in all_samples[language] if sample["metadata"]["split"] == split]
            sft_counts[language][split] = write_jsonl(
                OUTPUT_ROOT / "exports" / "text_sft" / f"{language}_{split}.jsonl", split_samples
            )
        eval_samples = [sample for sample in all_samples[language] if sample["metadata"]["split"] == "test"]
        write_jsonl(OUTPUT_ROOT / "exports" / "eval" / f"cases_{language}.jsonl", eval_samples)

    tts_fields = [
        "utterance_id", "voice_id", "audio_path", "audio_path_absolute", "text_jp", "speaker_id",
        "line_id", "scene_id", "voice_sha256", "voice_style", "relationship_stage", "outfit_code",
        "expression_code", "split", "duration_seconds", "codec", "sample_rate", "channels",
        "source_file", "source_row_number", "duplicate_source_line_ids",
    ]
    write_csv(OUTPUT_ROOT / "exports" / "tts" / "manifest.tsv", tts_rows, tts_fields, "\t")
    write_parquet(OUTPUT_ROOT / "exports" / "tts" / "manifest.parquet", tts_rows)
    write_csv(
        OUTPUT_ROOT / "exports" / "tts" / "excluded_duplicate_audio.csv",
        tts_duplicates,
        ["voice_sha256", "kept_line_id", "excluded_line_id", "reason"],
    )
    for split in ["train", "validation", "test"]:
        filelist = OUTPUT_ROOT / "exports" / "tts" / f"filelist_{split}.txt"
        lines = [
            f"{row['audio_path_absolute']}|{SPEAKER_JP}|JP|{row['text_jp']}"
            for row in tts_rows
            if row["split"] == split
        ]
        filelist.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")

    expression_rows = [
        {
            "sprite_id": row["sprite_id"],
            "outfit_code": row["outfit_code"],
            "expression_code": row["expression_code"],
            "size": row["size"],
            "expression_tag": row["expression_tag"],
            "expression_intensity": row["expression_intensity"],
            "project_path": row["project_path"],
            "excluded_default": row["excluded_default"],
            "label_status": row["label_status"],
            "source_file": row["source_file"],
            "source_row_number": row["source_row_number"],
            "build_id": build_id,
        }
        for row in sprites
    ]
    write_csv(
        OUTPUT_ROOT / "exports" / "expression_map" / "expression_map.csv",
        expression_rows,
        list(expression_rows[0].keys()),
    )
    write_json(OUTPUT_ROOT / "exports" / "expression_map" / "expression_map.json", expression_rows)
    knowledge_counts = write_knowledge(dialogues, rag_chunks)

    missing_voice = [row for row in voice_safe_enriched if not row["exists"]]
    decode_failures = [row for row in voice_safe_enriched if not row["decode_ok"]]
    missing_sprites = [row for row in sprites if not row["asset_exists"]]
    missing_assets = {
        "missing_training_voice_count": len(missing_voice),
        "decode_failure_count": len(decode_failures),
        "missing_sprite_count": len(missing_sprites),
        "missing_training_voices": missing_voice,
        "audio_decode_failures": decode_failures,
        "missing_sprites": missing_sprites,
    }
    write_json(OUTPUT_ROOT / "reports" / "missing_assets.json", missing_assets)
    write_json(OUTPUT_ROOT / "reports" / "duplicate_audio.json", duplicate_report)

    scene_split_sets: dict[str, set[str]] = defaultdict(set)
    for row in dialogues:
        scene_split_sets[row["scene_id"]].add(row["split"])
    cross_split_scenes = {scene: sorted(splits) for scene, splits in scene_split_sets.items() if len(splits) > 1}
    train_scenes = {row["scene_id"] for row in training_rows if row["split"] == "train"}
    validation_scenes = {row["scene_id"] for row in training_rows if row["split"] == "validation"}
    test_scenes = {row["scene_id"] for row in training_rows if row["split"] == "test"}
    split_report = {
        "scene_split_conflicts": cross_split_scenes,
        "train_validation_overlap": sorted(train_scenes & validation_scenes),
        "train_test_overlap": sorted(train_scenes & test_scenes),
        "validation_test_overlap": sorted(validation_scenes & test_scenes),
        "train_scene_count": len(train_scenes),
        "validation_scene_count": len(validation_scenes),
        "test_scene_count": len(test_scenes),
    }
    write_json(OUTPUT_ROOT / "reports" / "split_leakage.json", split_report)

    line_unique, duplicate_line_ids = verify_unique(dialogues, "line_id")
    scene_unique, duplicate_scene_ids = verify_unique(scenes, "scene_id")
    utterance_unique, duplicate_utterance_ids = verify_unique(voices, "utterance_id")
    sprite_unique, duplicate_sprite_ids = verify_unique(sprites, "sprite_id")
    broken_links = [
        row
        for row in links
        if (row["utterance_id"] and not row["voice_exists"]) or (row["sprite_id"] and not row["sprite_exists"])
    ]
    referential_report = {
        "line_id_unique": line_unique,
        "scene_id_unique": scene_unique,
        "utterance_id_unique": utterance_unique,
        "sprite_id_unique": sprite_unique,
        "duplicate_line_ids": duplicate_line_ids,
        "duplicate_scene_ids": duplicate_scene_ids,
        "duplicate_utterance_ids": duplicate_utterance_ids,
        "duplicate_sprite_ids": duplicate_sprite_ids,
        "broken_dialogue_asset_link_count": len(broken_links),
        "broken_dialogue_asset_links": broken_links,
    }
    write_json(OUTPUT_ROOT / "reports" / "referential_integrity.json", referential_report)

    parquet_counts = {
        name: pq.read_table(OUTPUT_ROOT / "canonical" / f"{name}.parquet").num_rows
        for name in ["dialogues", "scenes", "voices", "sprites", "external_sprite_refs", "dialogue_asset_links"]
    }
    tts_safe_only = all(
        row["voice_train_allowed"]
        and row["content_rating"] == "safe"
        and row["relationship_stage"] in KNOWN_RELATIONSHIPS
        for row in tts_rows
    )
    provenance_complete = all(
        row["source_file"] and row["source_row_number"] and row["source_line_id"] for row in training_rows
    ) and all(row["source_file"] and row["source_row_number"] for row in tts_rows)

    gates = {
        "line_scene_utterance_ids_unique": line_unique and scene_unique and utterance_unique,
        "sprite_ids_unique": sprite_unique,
        "all_training_audio_paths_exist": not missing_voice,
        "all_training_audio_decode": not decode_failures and not args.skip_audio_decode,
        "text_audio_conflicts_resolved": not duplicate_report["one_audio_multiple_text_conflicts"] and not duplicate_report["one_voice_id_multiple_hash_conflicts"],
        "no_scene_split_leakage": not cross_split_scenes and not (train_scenes & validation_scenes) and not (train_scenes & test_scenes) and not (validation_scenes & test_scenes),
        "tts_export_safe_only": tts_safe_only,
        "text_eval_scene_isolation": not (train_scenes & test_scenes),
        "jsonl_schema_valid": not schema_errors,
        "canonical_parquet_readback": parquet_counts["dialogues"] == len(dialogues) and parquet_counts["dialogue_asset_links"] == len(links),
        "export_provenance_complete": provenance_complete,
        "dataset_card_and_build_report_generated": True,
    }

    export_summary = {
        "build_id": build_id,
        "canonical": parquet_counts,
        "rag_chunks": rag_counts,
        "text_sft": sft_counts,
        "tts_unique_rows": len(tts_rows),
        "tts_duplicate_rows_excluded": len(tts_duplicates),
        "expression_map_rows": len(expression_rows),
        "eval_jp_rows": sft_counts["jp"].get("test", 0),
        "eval_zh_rows": sft_counts["zh"].get("test", 0),
        "knowledge": knowledge_counts,
    }
    write_json(OUTPUT_ROOT / "reports" / "export_summary.json", export_summary)
    write_json(OUTPUT_ROOT / "reports" / "training_sample_schema_errors.json", schema_errors)
    export_md = [
        "# Export Summary",
        "",
        f"- Build ID: `{build_id}`",
        f"- Canonical dialogues: {len(dialogues)}",
        f"- Canonical scenes: {len(scenes)}",
        f"- Canonical voices: {len(voices)}",
        f"- Canonical sprites: {len(sprites)}",
        f"- Safe formal text rows: {len(training_rows)} per language",
        f"- Unique TTS rows: {len(tts_rows)}",
        f"- Duplicate TTS rows excluded: {len(tts_duplicates)}",
        f"- RAG chunks: {sum(rag_counts.values())}",
        f"- Evaluation rows: JP {sft_counts['jp'].get('test', 0)}, ZH {sft_counts['zh'].get('test', 0)}",
        "",
        "All exports are generated from canonical tables and retain source_file/source_row_number or line_id provenance.",
    ]
    (OUTPUT_ROOT / "reports" / "export_summary.md").write_text("\n".join(export_md) + "\n", encoding="utf-8")

    dataset_card = [
        "# Meguri Training Dataset",
        "",
        f"- Build ID: `{build_id}`",
        f"- Input fingerprint: `{build_fingerprint}`",
        f"- Generated: {utc_now()}",
        "- Character: Meguri / 爱莉 / メグリ",
        "- Source game: 妹のおかげでモテすぎてヤバい。",
        "- License/usage: private research and development; verify source-game rights before distribution.",
        "",
        "## Intended Uses",
        "",
        "Prompt/RAG baselines, Japanese/Chinese character-text experiments, TTS training, expression mapping, and isolated evaluation.",
        "",
        "## Exclusions",
        "",
        "Explicit H scenes, suggestive rows, unknown relationship rows, missing assets, undecodable audio, and duplicate audio hashes are excluded from formal training exports.",
        "",
        "## Canonical Source",
        "",
        "The Parquet tables under canonical/ are authoritative. Files under source/ are immutable snapshots for audit only. All downstream exports are reproducible from this build script.",
        "",
        "## Split Policy",
        "",
        "Existing scene-level splits are preserved and normalized from dev to validation. No line-level random split is used.",
        "",
        "## Known Limitations",
        "",
        "Expression labels remain heuristic pending visual review. Relationship-stage unknown rows remain isolated. Full PII/privacy filtering is intentionally deferred according to Notion page 10.",
    ]
    (OUTPUT_ROOT / "dataset_card.md").write_text("\n".join(dataset_card) + "\n", encoding="utf-8")

    go = all(gates.values())
    go_report = {"build_id": build_id, "decision": "GO" if go else "NO-GO", "gates": gates}
    write_json(OUTPUT_ROOT / "reports" / "go_no_go.json", go_report)
    build_report = {
        "build_id": build_id,
        "input_fingerprint": build_fingerprint,
        "started_utc": build_started,
        "completed_utc": utc_now(),
        "decision": go_report["decision"],
        "gates": gates,
        "counts": export_summary,
        "input_file_count": len(inventory),
        "input_bytes": sum(row["bytes"] for row in inventory),
        "audio_validation_count": len(audio_validation),
        "audio_decode_failure_count": len(decode_failures),
        "duplicate_audio_hash_groups": len(duplicate_report["duplicate_hash_groups"]),
        "schema_error_count": len(schema_errors),
        "notion_requirement_source": "10｜训练数据仓重构与二次预处理 Go／No-Go",
        "builder": project_rel(Path(__file__)),
        "python": sys.version,
        "pyarrow": pa.__version__,
    }
    write_json(OUTPUT_ROOT / "build_report.json", build_report)

    processing_result = [
        "# Meguri 二次预处理结果",
        "",
        f"- 处理日期：{datetime.now().astimezone().isoformat(timespec='seconds')}",
        f"- Build ID：`{build_id}`",
        f"- Go/No-Go：`{'GO' if go else 'NO-GO'}`（最终 checksums 状态见 build_report.json）",
        "- 输入原则：只读使用 aligned_v1 和已有资源，不重新解包、不重新 OCR、不覆盖 data/meguri。",
        "",
        "## Canonical 表",
        "",
        f"- dialogues.parquet：{len(dialogues)} 行",
        f"- scenes.parquet：{len(scenes)} 行",
        f"- voices.parquet：{len(voices)} 个稳定语音记录",
        f"- sprites.parquet：{len(sprites)} 个稳定立绘记录",
        f"- external_sprite_refs.parquet：{len(external_sprite_rows)} 个外部角色引用记录",
        f"- dialogue_asset_links.parquet：{len(links)} 行",
        "",
        "## 训练导出",
        "",
        f"- RAG：{sum(rag_counts.values())} 个场景块（train {rag_counts['train']} / validation {rag_counts['validation']} / test {rag_counts['test']}）",
        f"- 文本 SFT JP：{sum(sft_counts['jp'].values())} 条（train {sft_counts['jp']['train']} / validation {sft_counts['jp']['validation']} / test {sft_counts['jp']['test']}）",
        f"- 文本 SFT ZH：{sum(sft_counts['zh'].values())} 条（train {sft_counts['zh']['train']} / validation {sft_counts['zh']['validation']} / test {sft_counts['zh']['test']}）",
        f"- TTS：{len(tts_rows)} 条唯一安全音频；重复哈希排除 {len(tts_duplicates)} 条",
        f"- 表情映射：{len(expression_rows)} 条",
        f"- 评测：JP {sft_counts['jp']['test']} 条，ZH {sft_counts['zh']['test']} 条",
        "",
        "## 安全与质量检查",
        "",
        f"- 安全语音验证：{len(audio_validation)} 个文件；缺失 {len(missing_voice)}；解码失败 {len(decode_failures)}",
        f"- 音频文本冲突：{len(duplicate_report['one_audio_multiple_text_conflicts'])}",
        f"- voice_id 多哈希冲突：{len(duplicate_report['one_voice_id_multiple_hash_conflicts'])}",
        f"- 场景切分泄漏：{len(cross_split_scenes)} 个场景",
        f"- JSON Schema 错误：{len(schema_errors)}",
        f"- 缺失立绘：{len(missing_sprites)}",
        f"- 导出 provenance 完整：{'是' if provenance_complete else '否'}",
        "",
        "## 训练入口",
        "",
        "- 训练数据根目录：datasets/meguri",
        "- 文本训练：exports/text_sft/jp_train.jsonl、zh_train.jsonl",
        "- 验证集：exports/text_sft/jp_validation.jsonl、zh_validation.jsonl",
        "- 评测集：exports/eval/cases_jp.jsonl、cases_zh.jsonl",
        "- TTS：exports/tts/manifest.tsv 或 filelist_train.txt",
        "- 训练前最终判定：reports/go_no_go.json 与 build_report.json",
        "",
        "## 已知限制",
        "",
        "表情标签仍是启发式初版，需视觉复核；unknown 关系阶段继续隔离；PII/隐私过滤按 Notion 10 页决定暂缓。",
    ]
    (OUTPUT_ROOT / "reports" / "processing_result.md").write_text("\n".join(processing_result) + "\n", encoding="utf-8")

    checksum_count, checksums_verified = write_checksums()
    if not checksums_verified:
        go = False
        go_report["decision"] = "NO-GO"
        go_report["gates"]["checksums_verified"] = False
    else:
        go_report["gates"]["checksums_verified"] = True
    write_json(OUTPUT_ROOT / "reports" / "go_no_go.json", go_report)
    build_report["decision"] = go_report["decision"]
    build_report["gates"] = go_report["gates"]
    build_report["checksum_entry_count"] = checksum_count
    build_report["checksums_verified"] = checksums_verified
    write_json(OUTPUT_ROOT / "build_report.json", build_report)
    checksum_count, checksums_verified = write_checksums()

    print(json.dumps({"build_id": build_id, "decision": build_report["decision"], "checksums_verified": checksums_verified, "counts": export_summary}, ensure_ascii=False, indent=2))
    return 0 if build_report["decision"] == "GO" and checksums_verified else 2


if __name__ == "__main__":
    raise SystemExit(main())
