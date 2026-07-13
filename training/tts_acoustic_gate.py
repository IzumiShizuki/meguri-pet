from __future__ import annotations

import argparse
import hashlib
import math
import re
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from statistics import mean, median
from typing import Any

from training.common import (
    BUILD_ID,
    CONFIG_ROOT,
    DATASET_ROOT,
    FFMPEG,
    REPORT_ROOT,
    collapse_text,
    ensure_output_dirs,
    read_delimited,
    read_json,
    run_command,
    utc_now,
    write_delimited,
    write_json,
)


MANIFEST = DATASET_ROOT / "exports" / "tts" / "manifest.tsv"
INVENTORY_CSV = REPORT_ROOT / "tts_acoustic_inventory.csv"
REVIEW_CSV = REPORT_ROOT / "tts_quality_review.csv"
SPLIT_MANIFEST = CONFIG_ROOT / "tts_split_manifest.tsv"

MEAN_VOLUME_RE = re.compile(r"mean_volume:\s*(-?(?:inf|\d+(?:\.\d+)?))\s*dB", re.I)
MAX_VOLUME_RE = re.compile(r"max_volume:\s*(-?(?:inf|\d+(?:\.\d+)?))\s*dB", re.I)
SILENCE_RE = re.compile(r"silence_duration:\s*(\d+(?:\.\d+)?)")
SILENCE_START_RE = re.compile(r"silence_start:\s*(\d+(?:\.\d+)?)")
SILENCE_END_RE = re.compile(r"silence_end:\s*(\d+(?:\.\d+)?)")


def parse_db(match: re.Match[str] | None) -> float | None:
    if not match:
        return None
    raw = match.group(1).lower()
    return None if "inf" in raw else float(raw)


def analyze_one(row: dict[str, str]) -> dict[str, Any]:
    audio_path = Path(row["audio_path_absolute"])
    duration = float(row.get("duration_seconds") or 0.0)
    result = run_command(
        [
            FFMPEG,
            "-hide_banner",
            "-nostdin",
            "-nostats",
            "-v",
            "info",
            "-i",
            audio_path,
            "-af",
            "silencedetect=noise=-50dB:d=0.10,volumedetect",
            "-f",
            "null",
            "NUL",
        ],
        timeout=max(30, int(duration * 4 + 10)),
    )
    output = result["stderr"] + "\n" + result["stdout"]
    mean_db = parse_db(MEAN_VOLUME_RE.search(output))
    peak_db = parse_db(MAX_VOLUME_RE.search(output))
    silence_durations = [float(value) for value in SILENCE_RE.findall(output)]
    silence_ratio = min(1.0, sum(silence_durations) / duration) if duration > 0 else 1.0
    silence_starts = [float(value) for value in SILENCE_START_RE.findall(output)]
    silence_ends = [float(value) for value in SILENCE_END_RE.findall(output)]
    starts_silent = bool(silence_starts and silence_starts[0] <= 0.02)
    ends_silent = bool(silence_ends and duration > 0 and duration - silence_ends[-1] <= 0.03)

    flags: list[str] = []
    reject_reasons: list[str] = []
    if result["returncode"] != 0 or mean_db is None or peak_db is None:
        reject_reasons.append("decode_or_analysis_failure")
    if duration < 0.35:
        reject_reasons.append("too_short")
    elif duration > 15.0:
        reject_reasons.append("too_long")
    if peak_db is not None and peak_db >= -0.1:
        reject_reasons.append("possible_clipping")
    if mean_db is not None and mean_db < -40.0:
        reject_reasons.append("very_low_level")
    if mean_db is not None and mean_db > -8.0:
        flags.append("high_rms")
    if silence_ratio > 0.55:
        flags.append("excessive_silence_needs_review")
    elif silence_ratio > 0.35:
        flags.append("high_silence")
    if not starts_silent:
        flags.append("no_detected_leading_silence")
    if not ends_silent:
        flags.append("no_detected_trailing_silence")
    if int(row.get("channels") or 0) != 1:
        flags.append("non_mono")
    if int(row.get("sample_rate") or 0) not in {32000, 44100, 48000}:
        flags.append("unusual_sample_rate")
    if row.get("voice_style") not in {
        "neutral", "soft", "cheerful", "restrained", "sleepy", "teasing",
        "affectionate", "worried",
    }:
        flags.append("voice_style_out_of_runtime_contract")

    return {
        **row,
        "text_jp_single_line": collapse_text(row.get("text_jp", "")),
        "mean_volume_db": mean_db,
        "peak_volume_db": peak_db,
        "silence_ratio": round(silence_ratio, 6),
        "starts_silent": starts_silent,
        "ends_silent": ends_silent,
        "analysis_ok": result["returncode"] == 0 and mean_db is not None and peak_db is not None,
        "automated_flags": ";".join(flags),
        "automated_reject_reasons": ";".join(reject_reasons),
        "automated_accepted": not reject_reasons,
    }


def duration_band(duration: float) -> str:
    if duration < 2.0:
        return "short"
    if duration < 5.0:
        return "medium"
    return "long"


def select_review_queue(rows: list[dict[str, Any]], count: int = 100) -> list[dict[str, Any]]:
    candidates = [row for row in rows if row.get("split") in {"train", "validation"}]
    selected: list[dict[str, Any]] = []
    selected_ids: set[str] = set()

    def stable_key(row: dict[str, Any]) -> str:
        value = str(row.get("voice_sha256") or row.get("utterance_id") or "")
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def add(row: dict[str, Any]) -> None:
        utterance_id = str(row.get("utterance_id"))
        if utterance_id not in selected_ids and len(selected) < count:
            selected.append(row)
            selected_ids.add(utterance_id)

    ordered = sorted(candidates, key=stable_key)
    flagged = [
        row for row in ordered if row.get("automated_flags") or row.get("automated_reject_reasons")
    ]
    for row in flagged[:20]:
        add(row)

    factor_functions = [
        lambda row: str(row.get("split")),
        lambda row: str(row.get("relationship_stage")),
        lambda row: str(row.get("voice_style")),
        lambda row: duration_band(float(row.get("duration_seconds") or 0)),
        lambda row: str(row.get("scene_id")),
        lambda row: str(row.get("expression_code")),
    ]
    factor_limits = [2, 3, 20, 3, 25, 35]
    for factor, factor_limit in zip(factor_functions, factor_limits):
        seen: set[str] = set()
        added = 0
        for row in ordered:
            key = factor(row)
            if key and key not in seen:
                before = len(selected)
                add(row)
                seen.add(key)
                if len(selected) > before:
                    added += 1
                if added >= factor_limit or len(selected) >= count:
                    break
    for row in ordered:
        add(row)
        if len(selected) >= count:
            break
    return selected


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] * (high - position) + ordered[high] * (position - low)


def duration_report(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        grouped[str(row.get("split"))].append(float(row.get("duration_seconds") or 0))
    result: dict[str, Any] = {"build_id": BUILD_ID, "splits": {}}
    for split, values in sorted(grouped.items()):
        result["splits"][split] = {
            "count": len(values),
            "total_hours": round(sum(values) / 3600, 6),
            "min_seconds": round(min(values), 6),
            "p25_seconds": round(percentile(values, 0.25), 6),
            "median_seconds": round(median(values), 6),
            "p75_seconds": round(percentile(values, 0.75), 6),
            "p95_seconds": round(percentile(values, 0.95), 6),
            "max_seconds": round(max(values), 6),
            "mean_seconds": round(mean(values), 6),
        }
    all_values = [value for values in grouped.values() for value in values]
    result["total_files"] = len(all_values)
    result["total_hours"] = round(sum(all_values) / 3600, 6)
    return result


def style_report(rows: list[dict[str, Any]]) -> dict[str, Any]:
    allowed = {
        "neutral", "soft", "cheerful", "restrained", "sleepy", "teasing",
        "affectionate", "worried",
    }
    grouped: dict[str, dict[str, Any]] = {}
    for style in sorted({str(row.get("voice_style") or "unknown") for row in rows}):
        style_rows = [row for row in rows if str(row.get("voice_style") or "unknown") == style]
        grouped[style] = {
            "runtime_contract_allowed": style in allowed,
            "count": len(style_rows),
            "duration_hours": round(
                sum(float(row.get("duration_seconds") or 0) for row in style_rows) / 3600, 6
            ),
            "splits": dict(Counter(str(row.get("split")) for row in style_rows)),
            "relationships": dict(Counter(str(row.get("relationship_stage")) for row in style_rows)),
        }
    return {"build_id": BUILD_ID, "styles": grouped}


def load_manual_reviews() -> dict[str, dict[str, str]]:
    if not REVIEW_CSV.is_file():
        return {}
    return {row.get("utterance_id", ""): row for row in read_delimited(REVIEW_CSV)}


def build_integrity_checks(rows: list[dict[str, Any]]) -> dict[str, Any]:
    hash_texts: dict[str, set[str]] = defaultdict(set)
    hash_splits: dict[str, set[str]] = defaultdict(set)
    scene_splits: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        digest = str(row.get("voice_sha256") or "")
        hash_texts[digest].add(collapse_text(str(row.get("text_jp") or "")))
        hash_splits[digest].add(str(row.get("split") or ""))
        scene_splits[str(row.get("scene_id") or "")].add(str(row.get("split") or ""))
    return {
        "duplicate_hashes": sorted(key for key, values in hash_texts.items() if key and len(values) == 1 and sum(1 for row in rows if row.get("voice_sha256") == key) > 1),
        "transcript_conflicts": sorted(key for key, values in hash_texts.items() if key and len(values) > 1),
        "hash_split_leakage": sorted(key for key, values in hash_splits.items() if key and len(values) > 1),
        "scene_split_leakage": sorted(key for key, values in scene_splits.items() if key and len(values) > 1),
    }


def write_manual_report(queue_count: int, reviewed: int, passed: int, rejected: int) -> None:
    lines = [
        "# TTS Manual Review",
        "",
        f"- Build ID: `{BUILD_ID}`",
        f"- Stratified queue: `{queue_count}`",
        f"- Reviewed: `{reviewed}`",
        f"- Passed: `{passed}`",
        f"- Rejected: `{rejected}`",
        "",
        "The queue excludes test audio so that test remains blind. It covers scene, duration, relationship stage, expression code, candidate voice style and acoustic anomaly strata.",
        "",
        "Review `reports/tts_quality_review.csv` and set `manual_status` to `pass` or `reject`. Record `manual_issue` using values such as `transcript_mismatch`, `bgm`, `se`, `noise`, `truncated`, `wrong_speaker` or `other`. Then rerun:",
        "",
        "```powershell",
        "D:\\environment\\anaconda3\\envs\\py314\\python.exe -m training.tts_acoustic_gate --finalize-only",
        "```",
        "",
        "Automated metrics do not count as listening. The Gate remains NO_GO until at least 100 rows have a human decision.",
    ]
    (REPORT_ROOT / "tts_manual_review.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def finalize(rows: list[dict[str, Any]], review_rows: list[dict[str, str]]) -> dict[str, Any]:
    reviews = {row.get("utterance_id", ""): row for row in review_rows}
    completed = [row for row in review_rows if row.get("manual_status", "").strip().lower() in {"pass", "reject"}]
    passed = [row for row in completed if row.get("manual_status", "").strip().lower() == "pass"]
    rejected = [row for row in completed if row.get("manual_status", "").strip().lower() == "reject"]
    rejected_ids = {row.get("utterance_id", "") for row in rejected}
    integrity = build_integrity_checks(rows)
    auto_rejected_ids = {
        str(row.get("utterance_id")) for row in rows if not bool(row.get("automated_accepted"))
    }
    excluded_ids = auto_rejected_ids | rejected_ids

    split_fields = list(read_delimited(MANIFEST, delimiter="\t")[0].keys()) + [
        "mean_volume_db",
        "peak_volume_db",
        "silence_ratio",
        "automated_flags",
        "automated_reject_reasons",
        "manual_status",
        "manual_issue",
        "training_eligible",
    ]
    split_rows: list[dict[str, Any]] = []
    for row in rows:
        review = reviews.get(str(row.get("utterance_id")), {})
        split_rows.append(
            {
                **row,
                "manual_status": review.get("manual_status", ""),
                "manual_issue": review.get("manual_issue", ""),
                "training_eligible": (
                    str(row.get("split")) in {"train", "validation"}
                    and str(row.get("utterance_id")) not in excluded_ids
                ),
            }
        )
    write_delimited(SPLIT_MANIFEST, split_rows, split_fields, delimiter="\t")

    manual_complete = len(completed) >= 100
    blocking_integrity = any(
        integrity[key]
        for key in ("transcript_conflicts", "hash_split_leakage", "scene_split_leakage")
    )
    decode_failures = sum(1 for row in rows if not bool(row.get("analysis_ok")))
    if not manual_complete or blocking_integrity or decode_failures:
        decision = "NO_GO"
    elif excluded_ids:
        decision = "CONDITIONAL_GO"
    else:
        decision = "GO"

    manual_bgm_se = sum(
        1 for row in rejected if row.get("manual_issue", "").strip().lower() in {"bgm", "se"}
    )
    manual_truncated = sum(
        1 for row in rejected if row.get("manual_issue", "").strip().lower() == "truncated"
    )
    gate = {
        "build_id": BUILD_ID,
        "generated_utc": utc_now(),
        "total_files": len(rows),
        "accepted_files": len(rows) - len(excluded_ids),
        "total_duration_hours": round(
            sum(float(row.get("duration_seconds") or 0) for row in rows) / 3600, 6
        ),
        "decode_failures": decode_failures,
        "transcript_conflicts": len(integrity["transcript_conflicts"]),
        "clipping_rejected": sum(
            1 for row in rows if "possible_clipping" in str(row.get("automated_reject_reasons"))
        ),
        "bgm_or_se_rejected": manual_bgm_se,
        "truncated_rejected": manual_truncated,
        "split_leakage": len(integrity["hash_split_leakage"]) + len(integrity["scene_split_leakage"]),
        "manual_review_queue_count": len(review_rows),
        "manual_review_count": len(completed),
        "manual_review_pass_count": len(passed),
        "manual_review_reject_count": len(rejected),
        "manual_review_passed": manual_complete,
        "automated_rejected": len(auto_rejected_ids),
        "integrity": integrity,
        "decision": decision,
        "training_allowed": decision in {"GO", "CONDITIONAL_GO"},
        "notes": [
            "BGM/SE, transcript match, truncation and speaker identity require human listening.",
            "Test audio is excluded from the manual tuning queue and reference selection.",
            "CONDITIONAL_GO means rejected utterances are excluded in configs/tts_split_manifest.tsv.",
        ],
    }
    write_json(REPORT_ROOT / "tts_acoustic_gate.json", gate)
    write_manual_report(len(review_rows), len(completed), len(passed), len(rejected))
    return gate


def run_audit(workers: int, limit: int | None = None) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    manifest_rows = read_delimited(MANIFEST, delimiter="\t")
    if limit:
        manifest_rows = manifest_rows[:limit]
    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(analyze_one, row): row for row in manifest_rows}
        for index, future in enumerate(as_completed(futures), start=1):
            results.append(future.result())
            if index % 100 == 0 or index == len(futures):
                print(f"acoustic audit: {index}/{len(futures)}")
    results.sort(key=lambda row: str(row.get("utterance_id")))

    inventory_fields = list(manifest_rows[0].keys()) + [
        "text_jp_single_line",
        "mean_volume_db",
        "peak_volume_db",
        "silence_ratio",
        "starts_silent",
        "ends_silent",
        "analysis_ok",
        "automated_flags",
        "automated_reject_reasons",
        "automated_accepted",
    ]
    write_delimited(INVENTORY_CSV, results, inventory_fields)
    write_json(REPORT_ROOT / "tts_duration_statistics.json", duration_report(results))
    write_json(REPORT_ROOT / "tts_style_distribution.json", style_report(results))

    prior_reviews = load_manual_reviews()
    queue = select_review_queue(results, count=min(100, len(results)))
    review_fields = [
        "utterance_id",
        "voice_id",
        "audio_path_absolute",
        "text_jp_single_line",
        "scene_id",
        "split",
        "relationship_stage",
        "voice_style",
        "expression_code",
        "duration_seconds",
        "mean_volume_db",
        "peak_volume_db",
        "silence_ratio",
        "automated_flags",
        "automated_reject_reasons",
        "manual_status",
        "manual_issue",
        "manual_notes",
        "reviewer",
        "reviewed_at",
    ]
    review_rows: list[dict[str, str]] = []
    for row in queue:
        utterance_id = str(row.get("utterance_id"))
        prior = prior_reviews.get(utterance_id, {})
        review_rows.append(
            {
                **{field: str(row.get(field, "")) for field in review_fields},
                "manual_status": prior.get("manual_status", ""),
                "manual_issue": prior.get("manual_issue", ""),
                "manual_notes": prior.get("manual_notes", ""),
                "reviewer": prior.get("reviewer", ""),
                "reviewed_at": prior.get("reviewed_at", ""),
            }
        )
    write_delimited(REVIEW_CSV, review_rows, review_fields)
    return results, review_rows


def load_inventory() -> list[dict[str, Any]]:
    rows = read_delimited(INVENTORY_CSV)
    for row in rows:
        for field in ("duration_seconds", "mean_volume_db", "peak_volume_db", "silence_ratio"):
            try:
                row[field] = float(row[field]) if row.get(field, "") != "" else None
            except ValueError:
                row[field] = None
        for field in ("analysis_ok", "automated_accepted"):
            row[field] = str(row.get(field, "")).lower() == "true"
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the non-destructive Meguri TTS acoustic gate")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--finalize-only", action="store_true")
    args = parser.parse_args()
    ensure_output_dirs()
    if args.finalize_only:
        if not INVENTORY_CSV.is_file() or not REVIEW_CSV.is_file():
            raise SystemExit("run the acoustic audit before --finalize-only")
        rows = load_inventory()
        review_rows = read_delimited(REVIEW_CSV)
    else:
        rows, review_rows = run_audit(args.workers, args.limit)
    gate = finalize(rows, review_rows)
    print(f"acoustic gate: {gate['decision']} reviewed={gate['manual_review_count']}/100")
    return 0 if gate["training_allowed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
