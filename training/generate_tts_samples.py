from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import soundfile as sf
import torch


GPT_ROOT = Path(r"D:\environment\projects\GPT-SoVITS")
os.chdir(GPT_ROOT)
sys.path.insert(0, str(GPT_ROOT))
sys.path.insert(0, str(GPT_ROOT / "GPT_SoVITS"))
sys.path.insert(0, str(GPT_ROOT / "GPT_SoVITS" / "eres2net"))

from GPT_SoVITS.TTS_infer_pack.TTS import TTS, TTS_Config  # noqa: E402


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate fixed GPT-SoVITS evaluation samples")
    parser.add_argument("--config", required=True)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--sentences", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--t2s-weights")
    parser.add_argument("--vits-weights")
    args = parser.parse_args()

    reference = json.loads(Path(args.reference).read_text(encoding="utf-8"))["primary"]
    sentence_payload = json.loads(Path(args.sentences).read_text(encoding="utf-8"))
    seed = int(sentence_payload.get("seed", 3407))
    unsupported = [sentence["id"] for sentence in sentence_payload["sentences"] if sentence["language"] != "ja"]
    if unsupported:
        raise RuntimeError(f"non-Japanese TTS generation is disabled; remove these sentences from the active config: {unsupported}")
    config = TTS_Config(args.config)
    pipeline = TTS(config)
    if args.t2s_weights:
        pipeline.init_t2s_weights(args.t2s_weights)
    if args.vits_weights:
        pipeline.init_vits_weights(args.vits_weights)
    output_root = Path(args.output)
    output_root.mkdir(parents=True, exist_ok=True)
    model_loaded_gpu_memory_mib = round(torch.cuda.memory_allocated() / 1024**2, 3) if torch.cuda.is_available() else 0.0
    results = []
    for sentence in sentence_payload["sentences"]:
        request = {
            "text": sentence["text"],
            "text_lang": sentence["language"],
            "ref_audio_path": reference["audio_path"],
            "aux_ref_audio_paths": [],
            "prompt_text": reference["text_jp"].replace("\n", " "),
            "prompt_lang": "ja",
            "top_k": 15,
            "top_p": 1.0,
            "temperature": 1.0,
            "text_split_method": "cut5",
            "batch_size": 1,
            "batch_threshold": 0.75,
            "split_bucket": True,
            "speed_factor": 1.0,
            "fragment_interval": 0.3,
            "seed": seed,
            "parallel_infer": True,
            "repetition_penalty": 1.35,
            "sample_steps": 32,
            "super_sampling": False,
            "streaming_mode": False,
        }
        if torch.cuda.is_available():
            torch.cuda.reset_peak_memory_stats()
        started = time.perf_counter()
        sample_rate, audio = next(pipeline.run(request))
        elapsed = time.perf_counter() - started
        target = output_root / f"{sentence['id']}.wav"
        sf.write(target, audio, sample_rate, subtype="PCM_16")
        duration = len(audio) / sample_rate
        results.append(
            {
                "id": sentence["id"],
                "language": sentence["language"],
                "text": sentence["text"],
                "output": str(target),
                "sample_rate": sample_rate,
                "duration_seconds": round(duration, 6),
                "inference_seconds": round(elapsed, 6),
                "rtf": round(elapsed / duration, 6) if duration else None,
                "peak_gpu_allocated_mib": round(torch.cuda.max_memory_allocated() / 1024**2, 3) if torch.cuda.is_available() else 0.0,
                "peak_gpu_reserved_mib": round(torch.cuda.max_memory_reserved() / 1024**2, 3) if torch.cuda.is_available() else 0.0,
                "sha256": sha256_file(target),
            }
        )
        print(f"generated {sentence['id']}: {duration:.2f}s, RTF={elapsed / duration:.3f}", flush=True)

    metrics = {
        "label": args.label,
        "reference": reference,
        "seed": seed,
        "t2s_weights": args.t2s_weights or str(config.t2s_weights_path),
        "vits_weights": args.vits_weights or str(config.vits_weights_path),
        "model_loaded_gpu_memory_mib": model_loaded_gpu_memory_mib,
        "samples": results,
        "mean_rtf": round(sum(item["rtf"] for item in results if item["rtf"] is not None) / len(results), 6),
        "max_peak_gpu_allocated_mib": max(item["peak_gpu_allocated_mib"] for item in results),
        "max_peak_gpu_reserved_mib": max(item["peak_gpu_reserved_mib"] for item in results),
    }
    (output_root / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
