from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from training.common import BUILD_ID, BASELINE_ROOT, REPORT_ROOT, read_delimited, read_json, write_json, utc_now


def select() -> dict[str, object]:
    gate = read_json(REPORT_ROOT / "tts_acoustic_gate.json")
    if gate.get("decision") not in {"GO", "CONDITIONAL_GO"}:
        raise RuntimeError("TTS acoustic gate is not GO or CONDITIONAL_GO")
    if int(gate.get("manual_review_count") or 0) < 100:
        raise RuntimeError("100 human listening decisions are required")
    rows = read_delimited(REPORT_ROOT / "tts_quality_review.csv")
    passed_ids = {
        row.get("utterance_id", "")
        for row in rows
        if row.get("manual_status", "").strip().lower() == "pass"
    }
    inventory = read_delimited(REPORT_ROOT / "tts_acoustic_inventory.csv")
    candidates: list[dict[str, object]] = []
    for row in inventory:
        if row.get("split") == "test" or row.get("split") not in {"train", "validation"}:
            continue
        if row.get("utterance_id") not in passed_ids:
            continue
        if not (5.0 <= float(row.get("duration_seconds") or 0) <= 10.0):
            continue
        if float(row.get("peak_volume_db") or -99) >= -1.0:
            continue
        if float(row.get("mean_volume_db") or -99) < -25.0:
            continue
        if float(row.get("silence_ratio") or 1) > 0.30:
            continue
        if row.get("relationship_stage") not in {"sibling", "pursuit", "lover"}:
            continue
        style = row.get("voice_style") or "neutral"
        if style not in {"neutral", "soft", "restrained", "cheerful", "sleepy"}:
            continue
        # Prefer a stable neutral/soft sample and deterministic ordering.
        style_penalty = {"neutral": 0, "soft": 1, "restrained": 2, "cheerful": 3, "sleepy": 4}.get(style, 9)
        duration_penalty = abs(float(row.get("duration_seconds") or 0) - 7.0)
        digest = hashlib.sha256(str(row.get("voice_sha256", "")).encode("utf-8")).hexdigest()
        candidates.append(
            {
                "utterance_id": row.get("utterance_id"),
                "voice_id": row.get("voice_id"),
                "audio_path": row.get("audio_path_absolute"),
                "split": row.get("split"),
                "scene_id": row.get("scene_id"),
                "text_jp": row.get("text_jp"),
                "voice_style": style,
                "duration_seconds": float(row.get("duration_seconds") or 0),
                "mean_volume_db": float(row.get("mean_volume_db") or 0),
                "peak_volume_db": float(row.get("peak_volume_db") or 0),
                "silence_ratio": float(row.get("silence_ratio") or 0),
                "sort_key": [style_penalty, duration_penalty, digest],
            }
        )
    candidates.sort(key=lambda item: item["sort_key"])
    if not candidates:
        raise RuntimeError("no manually passed 5-10 second reference candidate meets acoustic constraints")
    selected = candidates[:3]
    for candidate in selected:
        candidate.pop("sort_key", None)
    result = {
        "build_id": BUILD_ID,
        "generated_utc": utc_now(),
        "selection_policy": "manual_pass_only; train/validation only; 5-10 seconds; conservative level and silence bounds",
        "primary": selected[0],
        "alternates": selected[1:],
        "references": selected,
    }
    output = BASELINE_ROOT / "zero_shot" / "reference_selection.json"
    write_json(output, result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Select manually passed zero-shot reference candidates")
    parser.parse_args()
    try:
        result = select()
    except RuntimeError as exc:
        print(f"zero-shot reference selection blocked: {exc}")
        return 2
    print(f"zero-shot references selected: {len(result['references'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
