from __future__ import annotations

import argparse
from pathlib import Path

from training.common import PROJECT_ROOT, read_delimited, write_delimited, write_json


def build(output: Path) -> dict[str, object]:
    source = PROJECT_ROOT / "datasets" / "meguri" / "source" / "asset_indexes" / "voice_safe_index.csv"
    formal = PROJECT_ROOT / "configs" / "tts_split_manifest.tsv"
    rows = read_delimited(source)
    known_metrics = {row["utterance_id"]: row for row in read_delimited(formal, delimiter="\t")}
    if len(rows) != 2742:
        raise RuntimeError(f"expected 2742 safe voice rows, found {len(rows)}")

    result: list[dict[str, object]] = []
    seen_hashes: set[str] = set()
    for row in rows:
        if str(row.get("voice_sha256")) in seen_hashes:
            raise RuntimeError(f"duplicate voice hash in safe index: {row.get('voice_sha256')}")
        seen_hashes.add(str(row.get("voice_sha256")))
        split = "validation" if row.get("split") == "dev" else str(row.get("split"))
        if split not in {"train", "validation", "test"}:
            raise RuntimeError(f"unsupported split for {row.get('voice_id')}: {split}")
        prior = known_metrics.get(str(row.get("utterance_id")), {})
        relationship = str(row.get("relationship_stage") or "unknown")
        result.append(
            {
                "utterance_id": row.get("utterance_id"),
                "voice_id": row.get("voice_id"),
                "audio_path": row.get("audio_path"),
                "audio_path_absolute": row.get("audio_path_absolute"),
                "text_jp": row.get("text_jp"),
                "speaker_id": row.get("speaker_id"),
                "line_id": row.get("line_id"),
                "scene_id": row.get("scene_id"),
                "voice_sha256": row.get("voice_sha256"),
                "voice_style": row.get("voice_style") or "neutral",
                "relationship_stage": relationship,
                "outfit_code": row.get("outfit_code"),
                "expression_code": row.get("expression_code"),
                "split": split,
                "duration_seconds": row.get("duration_seconds"),
                "codec": row.get("codec"),
                "sample_rate": row.get("sample_rate"),
                "channels": row.get("channels"),
                "source_file": row.get("source_file"),
                "source_row_number": row.get("source_row_number"),
                "duplicate_source_line_ids": "[]",
                "mean_volume_db": prior.get("mean_volume_db", ""),
                "peak_volume_db": prior.get("peak_volume_db", ""),
                "silence_ratio": prior.get("silence_ratio", ""),
                "automated_flags": prior.get("automated_flags", ""),
                "automated_reject_reasons": prior.get("automated_reject_reasons", ""),
                "manual_status": prior.get("manual_status", "not_reviewed_extended_base"),
                "manual_issue": prior.get("manual_issue", ""),
                "training_eligible": "True" if split in {"train", "validation"} else "False",
                "extended_base_included": "True",
                "unknown_relationship_included": "True" if relationship == "unknown" else "False",
            }
        )

    fields = list(result[0].keys())
    write_delimited(output, result, fields, delimiter="\t")
    report = {
        "source": str(source),
        "output": str(output),
        "rows": len(result),
        "train_rows": sum(row["split"] == "train" for row in result),
        "validation_rows": sum(row["split"] == "validation" for row in result),
        "test_rows_reserved": sum(row["split"] == "test" for row in result),
        "unknown_relationship_rows": sum(row["relationship_stage"] == "unknown" for row in result),
        "unknown_train_rows": sum(row["relationship_stage"] == "unknown" and row["split"] == "train" for row in result),
        "unknown_validation_rows": sum(row["relationship_stage"] == "unknown" and row["split"] == "validation" for row in result),
        "policy": "experimental extended Japanese base training only; formal dataset contract remains unchanged",
    }
    write_json(output.with_suffix(".json"), report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Build an auditable extended Japanese TTS manifest")
    parser.add_argument("--output", type=Path, default=Path("configs") / "tts_extended_ja_manifest.tsv")
    args = parser.parse_args()
    report = build(args.output.resolve())
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
