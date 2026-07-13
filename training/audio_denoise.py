from __future__ import annotations

import hashlib
import json
import math
import os
import re
import subprocess
import wave
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from statistics import mean
from typing import Any

from training.common import FFMPEG, sha256_file, utc_now, write_json


MEAN_VOLUME_RE = re.compile(r"mean_volume:\s*(-?(?:inf|\d+(?:\.\d+)?))\s*dB", re.I)
MAX_VOLUME_RE = re.compile(r"max_volume:\s*(-?(?:inf|\d+(?:\.\d+)?))\s*dB", re.I)


def _parse_db(pattern: re.Pattern[str], output: str) -> float | None:
    match = pattern.search(output)
    if not match or match.group(1).lower() == "-inf":
        return None
    return float(match.group(1))


def _signature(rows: list[dict[str, str]], settings: dict[str, Any]) -> str:
    payload = {
        "settings": settings,
        "inputs": [
            {
                "utterance_id": row.get("utterance_id"),
                "voice_sha256": row.get("voice_sha256"),
                "split": row.get("split"),
            }
            for row in sorted(rows, key=lambda item: str(item.get("utterance_id")))
        ],
    }
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _wav_info(path: Path) -> dict[str, Any]:
    with wave.open(str(path), "rb") as handle:
        frames = handle.getnframes()
        sample_rate = handle.getframerate()
        return {
            "sample_rate": sample_rate,
            "channels": handle.getnchannels(),
            "sample_width_bytes": handle.getsampwidth(),
            "frames": frames,
            "duration_seconds": frames / sample_rate if sample_rate else 0.0,
        }


def _process_one(
    row: dict[str, str],
    output_root: Path,
    filter_chain: str,
    sample_rate: int,
) -> dict[str, Any]:
    source = Path(row["audio_path_absolute"])
    target = output_root / f"{source.stem}.wav"
    temporary = target.with_suffix(".tmp.wav")
    temporary.unlink(missing_ok=True)
    command = [
        str(FFMPEG),
        "-hide_banner",
        "-nostdin",
        "-y",
        "-i",
        str(source),
        "-af",
        f"{filter_chain},volumedetect",
        "-ar",
        str(sample_rate),
        "-ac",
        "1",
        "-c:a",
        "pcm_s16le",
        str(temporary),
    ]
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0 or not temporary.is_file():
        temporary.unlink(missing_ok=True)
        raise RuntimeError(
            f"denoise failed for {row.get('utterance_id')}: returncode={completed.returncode}; "
            f"stderr={completed.stderr[-1200:]}"
        )
    os.replace(temporary, target)
    info = _wav_info(target)
    source_duration = float(row.get("duration_seconds") or 0.0)
    output_duration = float(info["duration_seconds"])
    mean_db = _parse_db(MEAN_VOLUME_RE, completed.stderr)
    peak_db = _parse_db(MAX_VOLUME_RE, completed.stderr)
    source_mean = float(row["mean_volume_db"]) if row.get("mean_volume_db") else None
    checks = {
        "sample_rate": info["sample_rate"] == sample_rate,
        "mono": info["channels"] == 1,
        "pcm16": info["sample_width_bytes"] == 2,
        "duration_preserved": abs(output_duration - source_duration) <= 0.03,
        "finite_level": mean_db is not None and peak_db is not None,
        "not_clipped": peak_db is not None and peak_db < -0.1,
        "level_preserved": (
            source_mean is None or mean_db is None or abs(mean_db - source_mean) <= 3.0
        ),
    }
    return {
        "utterance_id": row.get("utterance_id"),
        "voice_id": row.get("voice_id"),
        "split": row.get("split"),
        "source": str(source),
        "source_sha256": row.get("voice_sha256"),
        "target": str(target),
        "target_sha256": sha256_file(target),
        "bytes": target.stat().st_size,
        "source_duration_seconds": source_duration,
        "output_duration_seconds": round(output_duration, 6),
        "duration_delta_seconds": round(output_duration - source_duration, 6),
        "source_mean_volume_db": source_mean,
        "output_mean_volume_db": mean_db,
        "output_peak_volume_db": peak_db,
        "checks": checks,
        "passed": all(checks.values()),
    }


def preprocess_audio(
    rows: list[dict[str, str]],
    output_root: Path,
    settings: dict[str, Any],
    audit_path: Path,
) -> dict[str, Any]:
    if not rows:
        raise RuntimeError("denoise preprocessing received no eligible audio rows")
    filter_chain = str(settings.get("filter_chain") or "").strip()
    if not filter_chain:
        raise RuntimeError("audio_preprocessing.filter_chain is required")
    sample_rate = int(settings.get("output_sample_rate") or 44100)
    workers = max(1, min(int(settings.get("workers") or 4), 8))
    signature = _signature(rows, settings)
    output_root.mkdir(parents=True, exist_ok=True)

    if audit_path.is_file():
        prior = json.loads(audit_path.read_text(encoding="utf-8"))
        outputs = prior.get("files") or []
        if (
            prior.get("signature") == signature
            and len(outputs) == len(rows)
            and all(Path(item["target"]).is_file() for item in outputs)
            and not prior.get("failed_files")
        ):
            prior["reused"] = True
            return prior

    results: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(_process_one, row, output_root, filter_chain, sample_rate): row
            for row in rows
        }
        for index, future in enumerate(as_completed(futures), start=1):
            row = futures[future]
            try:
                results.append(future.result())
            except Exception as exc:
                failures.append(
                    {
                        "utterance_id": str(row.get("utterance_id")),
                        "source": str(row.get("audio_path_absolute")),
                        "error": f"{type(exc).__name__}: {exc}",
                    }
                )
            if index % 100 == 0 or index == len(futures):
                print(f"denoise preprocessing: {index}/{len(futures)}", flush=True)

    results.sort(key=lambda item: str(item["utterance_id"]))
    failed_quality = [item for item in results if not item["passed"]]
    mean_deltas = [
        item["output_mean_volume_db"] - item["source_mean_volume_db"]
        for item in results
        if item["output_mean_volume_db"] is not None and item["source_mean_volume_db"] is not None
    ]
    duration_deltas = [abs(float(item["duration_delta_seconds"])) for item in results]
    audit = {
        "generated_utc": utc_now(),
        "signature": signature,
        "mode": settings.get("mode", "conservative_spectral_denoise"),
        "filter_chain": filter_chain,
        "output_sample_rate": sample_rate,
        "workers": workers,
        "input_files": len(rows),
        "output_files": len(results),
        "failed_files": failures,
        "failed_quality_checks": len(failed_quality),
        "summary": {
            "mean_level_delta_db": round(mean(mean_deltas), 6) if mean_deltas else None,
            "max_absolute_duration_delta_seconds": round(max(duration_deltas), 6) if duration_deltas else None,
            "output_bytes": sum(int(item["bytes"]) for item in results),
            "clipped_outputs": sum(
                1 for item in results if not item["checks"].get("not_clipped", False)
            ),
            "nonfinite_values": sum(
                1 for item in results if not item["checks"].get("finite_level", False)
            ),
        },
        "files": results,
        "reused": False,
    }
    write_json(audit_path, audit)
    if failures or failed_quality or len(results) != len(rows):
        raise RuntimeError(
            "denoise quality gate failed: "
            f"processing_failures={len(failures)}, quality_failures={len(failed_quality)}, "
            f"expected={len(rows)}, actual={len(results)}"
        )
    if any(not math.isfinite(value) for value in mean_deltas + duration_deltas):
        raise RuntimeError("denoise quality gate found non-finite aggregate values")
    return audit
