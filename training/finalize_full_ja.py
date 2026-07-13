from __future__ import annotations

import hashlib
import json
import re
import statistics
from datetime import datetime
from pathlib import Path

from training.finalize_tts_baseline import acoustic_summary, inspect_wav


ROOT = Path(__file__).resolve().parents[1]
BUILD_ID = "meguri_v2_02c3db0c507d7c2d"
MODEL = Path(r"D:\AI\models\meguri\gpt-sovits\meguri_v2_02c3db0c507d7c2d\full_ja_001")
BASELINE = Path(r"D:\AI\models\meguri\gpt-sovits\meguri_v2_02c3db0c507d7c2d\baseline_001")
REPORTS = ROOT / "reports"
GPT_COMMIT = "551918539c8e3a496a504a4d9aa2cd7d591097bf"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def duration_seconds(log: Path) -> float:
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    start = next(line.split("=", 1)[1] for line in lines if line.startswith("started_utc="))
    finish = next(line.split("=", 1)[1] for line in reversed(lines) if line.startswith("finished_utc="))
    return round((datetime.fromisoformat(finish) - datetime.fromisoformat(start)).total_seconds(), 3)


def gpt_final_metrics(log: Path) -> dict[str, float | None]:
    text = log.read_text(encoding="utf-8", errors="replace")
    losses = re.findall(r"total_loss_epoch:\s*([0-9.]+)", text)
    accuracies = re.findall(r"top_3_acc_epoch:\s*([0-9.]+)", text)
    return {"total_loss_epoch": float(losses[-1]) if losses else None, "top_3_acc_epoch": float(accuracies[-1]) if accuracies else None}


def main() -> int:
    REPORTS.mkdir(parents=True, exist_ok=True)
    full_samples = json.loads((MODEL / "samples" / "metrics.json").read_text(encoding="utf-8"))
    if len(full_samples["samples"]) != 10 or any(row["language"] != "ja" for row in full_samples["samples"]):
        raise RuntimeError("full evaluation must contain exactly 10 Japanese samples")
    final_gpt = MODEL / "checkpoints" / "gpt_weights" / "meguri_full_ja_001-e20.ckpt"
    final_sovits = MODEL / "checkpoints" / "sovits_weights" / "meguri_full_ja_001_e20_s6560.pth"
    gpt_weights = sorted((MODEL / "checkpoints" / "gpt_weights").glob("meguri_full_ja_001-e*.ckpt"))
    sovits_weights = sorted((MODEL / "checkpoints" / "sovits_weights").glob("meguri_full_ja_001_e*_s*.pth"))
    if not final_gpt.is_file() or not final_sovits.is_file() or len(gpt_weights) != 20 or len(sovits_weights) != 20:
        raise RuntimeError(f"final weights/checkpoint count invalid: GPT={len(gpt_weights)}, SoVITS={len(sovits_weights)}")
    full_wavs = [inspect_wav(Path(row["output"])) for row in full_samples["samples"]]
    if any(row["sample_rate"] != 32000 or row["channels"] != 1 for row in full_wavs):
        raise RuntimeError("full evaluation WAV format check failed")
    warm_rtf = [float(row["rtf"]) for row in full_samples["samples"][1:]]
    baseline_metrics = json.loads((BASELINE / "metrics.json").read_text(encoding="utf-8"))
    baseline_warm = baseline_metrics["evaluation"]["fine_tuned_warm"]["mean_rtf"]
    gpt_log = MODEL / "logs" / "orchestrator" / "train_gpt.log"
    sovits_log = MODEL / "logs" / "orchestrator" / "train_sovits.log"
    gpt_metrics = gpt_final_metrics(gpt_log)
    metrics = {
        "build_id": BUILD_ID,
        "run_id": "full_ja_001",
        "status": "FULL_TRAINING_COMPLETE",
        "release_status": "PENDING_FINAL_JA_LISTENING",
        "active_language_scope": ["ja"],
        "excluded_languages": ["zh"],
        "training": {
            "framework": "GPT-SoVITS v2Pro",
            "framework_commit": GPT_COMMIT,
            "seed": 3407,
            "epochs": {"gpt": 20, "sovits": 20},
            "input_train_rows": 1306,
            "gpt_effective_rows_after_framework_filter": 1275,
            "sovits_rows": 1306,
            "batch_size": 4,
            "precision": "fp16",
            "test_rows_used_for_training": 0,
            "gpt_wall_seconds": duration_seconds(gpt_log),
            "sovits_wall_seconds": duration_seconds(sovits_log),
            "gpt_final_metrics": gpt_metrics,
            "observed_peak_training_memory_mib": 10624,
        },
        "evaluation": {
            "language": "ja",
            "fixed_samples": 10,
            "mean_rtf": round(statistics.mean(warm_rtf), 6),
            "median_rtf": round(statistics.median(warm_rtf), 6),
            "max_peak_gpu_allocated_mib": full_samples["max_peak_gpu_allocated_mib"],
            "max_peak_gpu_reserved_mib": full_samples["max_peak_gpu_reserved_mib"],
            "acoustic": acoustic_summary(full_wavs),
            "baseline_finetuned_warm_mean_rtf": baseline_warm,
            "samples": full_wavs,
        },
        "final_weights": {
            "gpt": {"path": str(final_gpt), "sha256": sha256(final_gpt), "bytes": final_gpt.stat().st_size},
            "sovits": {"path": str(final_sovits), "sha256": sha256(final_sovits), "bytes": final_sovits.stat().st_size},
        },
        "constraints": {"tts_runtime_scope": "local desktop pet only", "astrbot_tts": False, "website_tts": False, "cloud_server_tts": False, "formal_dataset_modified": False, "weights_uploaded": False},
    }
    (MODEL / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (MODEL / "framework_commit.txt").write_text(f"commit={GPT_COMMIT}\ndescribe=5519185-dirty\n", encoding="utf-8")
    (MODEL / "environment.json").write_text((REPORTS / "software_inventory.json").read_text(encoding="utf-8"), encoding="utf-8")
    (MODEL / "training_config.yaml").write_text(
        "\n".join([
            f"build_id: {BUILD_ID}", "run_id: full_ja_001", "framework: GPT-SoVITS", "framework_version: v2Pro",
            f"framework_commit: {GPT_COMMIT}", "active_language_scope: [ja]", "excluded_languages: [zh]",
            "seed: 3407", "precision: fp16", "batch_size: 4", "gpt_epochs: 20", "sovits_epochs: 20",
            "test_used_for_training: false", "test_used_for_reference_selection: false", "test_used_for_hyperparameter_tuning: false",
            "gpt_config: training_s1.yaml", "sovits_config: training_s2.json", "inference_config: tts_infer_v2pro_full_ja.yaml", "",
        ]), encoding="utf-8"
    )
    (MODEL / "tts_infer_v2pro_full_ja.yaml").write_text((ROOT / "configs" / "tts_infer_v2pro_full_ja.yaml").read_text(encoding="utf-8"), encoding="utf-8")
    model_card = f"""# Meguri GPT-SoVITS v2Pro full Japanese model

- Build: `{BUILD_ID}`; run: `full_ja_001`
- Status: GPT 20 epoch and SoVITS 20 epoch training complete; final listening/release review remains separate.
- Active scope: Japanese voice only. Chinese historical smoke-test artifacts are excluded from all current and future voice development.
- Training: batch 4, FP16, seed 3407; 1306 isolated rows, GPT effective rows 1275, test rows used 0.
- Final GPT: `{final_gpt}`
- Final SoVITS: `{final_sovits}`
- Fixed evaluation: 10 Japanese sentences, 32 kHz mono PCM.
- Warm RTF: `{metrics['evaluation']['mean_rtf']}`; baseline e4 warm RTF: `{baseline_warm}`.
- TTS runtime scope: local desktop pet only. AstrBot, website, and cloud server remain disabled.
- Text policy: closed LLM + Prompt + RAG; no text LoRA.

See `metrics.json`, `training_config.yaml`, `framework_commit.txt`, and `checksums.sha256`.
"""
    (MODEL / "model_card.md").write_text(model_card, encoding="utf-8")
    (MODEL / "MODEL_CARD.md").write_text(model_card, encoding="utf-8")
    report = f"""# 日语完整训练报告

`full_ja_001` 已完成 GPT 20 epoch 与 SoVITS 20 epoch。中文语音开发已停止，中文历史样本不参与本模型的训练或评测。

- 训练集：1306 条；GPT 有效 1275 条；测试集参与训练 0 条。
- 最终 GPT SHA-256：`{sha256(final_gpt)}`
- 最终 SoVITS SHA-256：`{sha256(final_sovits)}`
- 固定评测：10 条日语，全部成功生成并通过 32 kHz 单声道校验。
- GPT 最终 loss：`{gpt_metrics['total_loss_epoch']}`；top-3 accuracy：`{gpt_metrics['top_3_acc_epoch']}`。
- 完整模型热启动 RTF：`{metrics['evaluation']['mean_rtf']}`；4 epoch 基线：`{baseline_warm}`。
- 发布状态：`PENDING_FINAL_JA_LISTENING`；权重尚未部署到任何服务。
"""
    (REPORTS / "full_ja_training_report.md").write_text(report, encoding="utf-8")
    (REPORTS / "full_ja_release_decision.json").write_text(json.dumps({"build_id": BUILD_ID, "run_id": "full_ja_001", "training_complete": True, "release_status": "PENDING_FINAL_JA_LISTENING", "active_language_scope": ["ja"], "excluded_languages": ["zh"], "deployment_approved": False, "final_weights_verified": True, "next_action": "Listen to the 10 final Japanese samples before local desktop-pet release."}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksum_files = [final_gpt, final_sovits, MODEL / "training_config.json", MODEL / "training_config.yaml", MODEL / "training_s1.yaml", MODEL / "training_s2.json", MODEL / "tts_infer_v2pro_full_ja.yaml", MODEL / "dataset_build_id.txt", MODEL / "dataset_checksums.txt", MODEL / "framework_commit.txt", MODEL / "environment.json", MODEL / "metrics.json", MODEL / "model_card.md"] + sorted(MODEL.glob("samples/jp_*.wav"))
    (MODEL / "checksums.sha256").write_text("\n".join(f"{sha256(path)} *{path.relative_to(MODEL).as_posix()}" for path in checksum_files) + "\n", encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
