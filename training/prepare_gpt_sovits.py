from __future__ import annotations

import argparse
import json
from pathlib import Path

from training.common import (
    BUILD_ID,
    CONFIG_ROOT,
    MODEL_ROOT,
    PROJECT_ROOT,
    REPORT_ROOT,
    collapse_text,
    read_delimited,
    read_json,
    sha256_file,
    utc_now,
    write_json,
)


def prepare() -> Path:
    verification = read_json(REPORT_ROOT / "training_input_verification.json")
    acoustic = read_json(REPORT_ROOT / "tts_acoustic_gate.json")
    if verification.get("decision") != "GO":
        raise RuntimeError("training input verification is not GO")
    if acoustic.get("decision") not in {"GO", "CONDITIONAL_GO"}:
        raise RuntimeError("TTS acoustic gate is not GO or CONDITIONAL_GO")
    if int(acoustic.get("manual_review_count") or 0) < 100:
        raise RuntimeError("100 human listening decisions are required")

    config = read_json(CONFIG_ROOT / "tts_baseline.json")
    work_root = PROJECT_ROOT / "training" / "tts_work" / BUILD_ID / "baseline_001"
    filelist_root = work_root / "filelists"
    filelist_root.mkdir(parents=True, exist_ok=True)
    split_rows = read_delimited(CONFIG_ROOT / "tts_split_manifest.tsv", delimiter="\t")
    eligible = [row for row in split_rows if row.get("training_eligible", "").lower() == "true"]
    for split in ("train", "validation"):
        rows = [row for row in eligible if row.get("split") == split]
        lines = [
            "|".join(
                [
                    row["audio_path_absolute"],
                    "メグリ",
                    "JP",
                    collapse_text(row["text_jp"]),
                ]
            )
            for row in rows
        ]
        target = filelist_root / f"{split}.list"
        target.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")

    test_rows = [row for row in split_rows if row.get("split") == "test"]
    audit = {
        "build_id": BUILD_ID,
        "generated_utc": utc_now(),
        "train_rows": sum(1 for row in eligible if row.get("split") == "train"),
        "validation_rows": sum(1 for row in eligible if row.get("split") == "validation"),
        "test_rows_reserved": len(test_rows),
        "test_written_to_training_filelist": False,
        "filelist_sha256": {
            split: sha256_file(filelist_root / f"{split}.list") for split in ("train", "validation")
        },
        "framework_config": config,
    }
    write_json(work_root / "preparation_audit.json", audit)

    output_root = Path(config["output_root"])
    for relative in ("checkpoints", "samples", "logs"):
        (output_root / relative).mkdir(parents=True, exist_ok=True)
    (output_root / "dataset_build_id.txt").write_text(BUILD_ID + "\n", encoding="ascii")
    (output_root / "dataset_checksums.txt").write_text(
        (PROJECT_ROOT / "datasets" / "meguri" / "checksums.sha256").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (output_root / "training_config.json").write_text(
        json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return work_root


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare an isolated GPT-SoVITS baseline workspace")
    parser.parse_args()
    work_root = prepare()
    print(f"prepared GPT-SoVITS workspace: {work_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
