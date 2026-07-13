from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from training.audio_denoise import preprocess_audio
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


def prepare(config_path: Path | None = None) -> Path:
    verification = read_json(REPORT_ROOT / "training_input_verification.json")
    acoustic = read_json(REPORT_ROOT / "tts_acoustic_gate.json")
    if verification.get("decision") != "GO":
        raise RuntimeError("training input verification is not GO")
    if acoustic.get("decision") not in {"GO", "CONDITIONAL_GO"}:
        raise RuntimeError("TTS acoustic gate is not GO or CONDITIONAL_GO")
    if int(acoustic.get("manual_review_count") or 0) < 100:
        raise RuntimeError("100 human listening decisions are required")

    selected_config_path = config_path or (CONFIG_ROOT / "tts_baseline.json")
    config = read_json(selected_config_path)
    run_id = str(config.get("run_id") or "baseline_001")
    work_root = PROJECT_ROOT / "training" / "tts_work" / BUILD_ID / run_id
    filelist_root = work_root / "filelists"
    audio_root = work_root / "audio"
    filelist_root.mkdir(parents=True, exist_ok=True)
    audio_root.mkdir(parents=True, exist_ok=True)
    manifest_path = Path(config.get("training_manifest") or (CONFIG_ROOT / "tts_split_manifest.tsv"))
    split_rows = read_delimited(manifest_path, delimiter="\t")
    eligible = [row for row in split_rows if row.get("training_eligible", "").lower() == "true"]
    preprocessing = config.get("audio_preprocessing") or {}
    denoise_enabled = bool(preprocessing.get("enabled"))
    denoise_audit: dict[str, object] | None = None
    denoised_by_source: dict[str, dict[str, object]] = {}
    if denoise_enabled:
        denoise_audit = preprocess_audio(
            eligible,
            audio_root,
            preprocessing,
            work_root / "denoise_audit.json",
        )
        denoised_by_source = {
            str(Path(item["source"]).resolve()).lower(): item
            for item in denoise_audit["files"]
        }
    copied: list[dict[str, object]] = []
    seen_names: dict[str, str] = {}
    for split in ("train", "validation"):
        rows = [row for row in eligible if row.get("split") == split]
        lines: list[str] = []
        for row in rows:
            source = Path(row["audio_path_absolute"])
            name = source.name
            prior = seen_names.get(name)
            if prior and prior != row.get("voice_sha256"):
                raise RuntimeError(f"audio basename collision with different hashes: {name}")
            seen_names[name] = str(row.get("voice_sha256"))
            if denoise_enabled:
                denoised = denoised_by_source.get(str(source.resolve()).lower())
                if not denoised:
                    raise RuntimeError(f"missing denoised output for {source}")
                target = Path(str(denoised["target"]))
            else:
                target = audio_root / name
                if not target.exists():
                    shutil.copy2(source, target)
            lines.append(
                "|".join(
                    [
                        str(target),
                        "メグリ",
                        "JP",
                        collapse_text(row["text_jp"]),
                    ]
                )
            )
            copied.append(
                {
                    "name": name,
                    "source": str(source),
                    "target": str(target),
                    "voice_sha256": row.get("voice_sha256"),
                    "split": split,
                    "bytes": target.stat().st_size,
                    "preprocessed": denoise_enabled,
                }
            )
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
        "audio_root": str(audio_root),
        "copied_audio_count": len(copied),
        "copied_audio_bytes": sum(int(item["bytes"]) for item in copied),
        "copied_audio": copied,
        "audio_preprocessing": denoise_audit,
        "filelist_sha256": {
            split: sha256_file(filelist_root / f"{split}.list") for split in ("train", "validation")
        },
        "framework_config": config,
        "config_path": str(selected_config_path),
        "training_manifest": str(manifest_path),
    }
    write_json(work_root / "preparation_audit.json", audit)

    reference_source = Path(
        config.get("reference_selection_source")
        or (PROJECT_ROOT / "baselines" / "zero_shot" / "reference_selection.json")
    )
    if reference_source.is_file():
        reference = read_json(reference_source)
        if denoise_enabled:
            for key in ("primary",):
                item = reference.get(key)
                if item:
                    original = Path(item["audio_path"])
                    denoised = denoised_by_source.get(str(original.resolve()).lower())
                    if not denoised:
                        raise RuntimeError(f"selected reference was not denoised: {original}")
                    item["source_audio_path"] = str(original)
                    item["audio_path"] = str(denoised["target"])
            for item in reference.get("alternates") or []:
                original = Path(item["audio_path"])
                denoised = denoised_by_source.get(str(original.resolve()).lower())
                if denoised:
                    item["source_audio_path"] = str(original)
                    item["audio_path"] = str(denoised["target"])
            reference["preprocessing"] = {
                "mode": preprocessing.get("mode"),
                "filter_chain": preprocessing.get("filter_chain"),
                "denoise_signature": denoise_audit["signature"] if denoise_audit else None,
            }
        write_json(work_root / "reference_selection.json", reference)

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
    parser.add_argument("--config", type=Path, default=CONFIG_ROOT / "tts_baseline.json")
    args = parser.parse_args()
    try:
        work_root = prepare(args.config)
    except RuntimeError as exc:
        print(f"GPT-SoVITS preparation blocked: {exc}")
        return 2
    print(f"prepared GPT-SoVITS workspace: {work_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
