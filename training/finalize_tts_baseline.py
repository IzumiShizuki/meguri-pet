from __future__ import annotations

import csv
import hashlib
import json
import math
import random
import shutil
import statistics
import wave
from array import array
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / "configs" / "tts_baseline.json").read_text(encoding="utf-8"))
BUILD_ID = CONFIG["build_id"]
MODEL_ROOT = Path(CONFIG["output_root"])
ZERO_ROOT = ROOT / "baselines" / "zero_shot" / "samples"
FINE_ROOT = MODEL_ROOT / "samples"
BLIND_ROOT = ROOT / "baselines" / "blind_ab" / "baseline_001"
REPORT_ROOT = ROOT / "reports"
FRAMEWORK_COMMIT = "551918539c8e3a496a504a4d9aa2cd7d591097bf"
TARGET_LANGUAGE = "ja"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def warm_stats(payload: dict[str, Any]) -> dict[str, float]:
    seen: set[str] = set()
    warm: list[float] = []
    for sample in payload["samples"]:
        language = sample["language"]
        if language not in seen:
            seen.add(language)
            continue
        warm.append(float(sample["rtf"]))
    return {
        "count": len(warm),
        "mean_rtf": round(statistics.mean(warm), 6),
        "median_rtf": round(statistics.median(warm), 6),
        "max_rtf": round(max(warm), 6),
    }


def inspect_wav(path: Path) -> dict[str, Any]:
    with wave.open(str(path), "rb") as handle:
        rate = handle.getframerate()
        channels = handle.getnchannels()
        width = handle.getsampwidth()
        frames = handle.getnframes()
        samples = array("h", handle.readframes(frames))
    if rate != 32000 or channels != 1 or width != 2 or frames <= 0:
        raise RuntimeError(f"invalid WAV: {path}")
    absolute = [abs(value) for value in samples]
    peak = max(absolute, default=0)
    rms = math.sqrt(sum(value * value for value in samples) / len(samples)) if samples else 0.0
    silence_limit = 32767 * 10 ** (-50 / 20)
    return {
        "name": path.name,
        "sample_rate": rate,
        "channels": channels,
        "duration_seconds": round(frames / rate, 6),
        "peak_dbfs": round(20 * math.log10(max(peak, 1) / 32767), 3),
        "rms_dbfs": round(20 * math.log10(max(rms, 1) / 32767), 3),
        "silence_ratio_below_minus_50_dbfs": round(sum(value <= silence_limit for value in absolute) / len(absolute), 6),
        "clipping_sample_ratio": round(sum(value >= 32760 for value in absolute) / len(absolute), 8),
        "sha256": sha256(path),
    }


def validate_wavs(root: Path, expected: int, prefix: str = "") -> list[dict[str, Any]]:
    rows = [inspect_wav(path) for path in sorted(root.glob(f"{prefix}*.wav"))]
    if len(rows) != expected:
        raise RuntimeError(f"expected {expected} WAVs in {root}, found {len(rows)}")
    return rows


def acoustic_summary(rows: list[dict[str, Any]]) -> dict[str, float]:
    return {
        "mean_duration_seconds": round(statistics.mean(row["duration_seconds"] for row in rows), 6),
        "mean_peak_dbfs": round(statistics.mean(row["peak_dbfs"] for row in rows), 3),
        "mean_rms_dbfs": round(statistics.mean(row["rms_dbfs"] for row in rows), 3),
        "mean_silence_ratio": round(statistics.mean(row["silence_ratio_below_minus_50_dbfs"] for row in rows), 6),
        "max_clipping_sample_ratio": max(row["clipping_sample_ratio"] for row in rows),
    }


def level_match_pair(source_a: Path, source_b: Path, target_a: Path, target_b: Path) -> tuple[float, float]:
    def read_pcm(path: Path) -> tuple[Any, array, float]:
        with wave.open(str(path), "rb") as handle:
            params = handle.getparams()
            samples = array("h", handle.readframes(handle.getnframes()))
        rms = math.sqrt(sum(value * value for value in samples) / len(samples)) if samples else 0.0
        return params, samples, rms

    params_a, samples_a, rms_a = read_pcm(source_a)
    params_b, samples_b, rms_b = read_pcm(source_b)
    if params_a.nchannels != 1 or params_b.nchannels != 1 or params_a.sampwidth != 2 or params_b.sampwidth != 2:
        raise RuntimeError("blind level matching requires mono PCM16")
    target_rms = min(rms_a, rms_b)
    gains = [target_rms / rms_a if rms_a else 1.0, target_rms / rms_b if rms_b else 1.0]
    for target, params, samples, gain in (
        (target_a, params_a, samples_a, gains[0]),
        (target_b, params_b, samples_b, gains[1]),
    ):
        adjusted = array("h", (max(-32768, min(32767, round(value * gain))) for value in samples))
        target.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(target), "wb") as handle:
            handle.setparams(params)
            handle.writeframes(adjusted.tobytes())
    gain_db_a = 20 * math.log10(gains[0]) if gains[0] > 0 else -120.0
    gain_db_b = 20 * math.log10(gains[1]) if gains[1] > 0 else -120.0
    return round(gain_db_a, 4), round(gain_db_b, 4)


def build_blind_pack(zero: dict[str, Any], fine: dict[str, Any]) -> list[dict[str, Any]]:
    review_path = REPORT_ROOT / "tts_finetune_ab_review.csv"
    if review_path.is_file():
        existing = list(csv.DictReader(review_path.open("r", encoding="utf-8-sig", newline="")))
        if any(row.get("preference_A_B_TIE", "").strip() for row in existing):
            raise RuntimeError("blind review already contains ratings; refusing to overwrite it")
    raw_root = BLIND_ROOT / "raw"
    matched_root = BLIND_ROOT / "level_matched"
    raw_root.mkdir(parents=True, exist_ok=True)
    matched_root.mkdir(parents=True, exist_ok=True)
    rng = random.Random(3407)
    fine_is_a_assignments = [True] * ((len(zero["samples"]) + 1) // 2) + [False] * (len(zero["samples"]) // 2)
    rng.shuffle(fine_is_a_assignments)
    key_rows: list[dict[str, Any]] = []
    review_rows: list[list[Any]] = []
    for index, (before, after) in enumerate(zip(zero["samples"], fine["samples"], strict=True), start=1):
        pair_id = f"pair_{index:03d}"
        fine_is_a = fine_is_a_assignments[index - 1]
        source_a = Path(after["output"] if fine_is_a else before["output"])
        source_b = Path(before["output"] if fine_is_a else after["output"])
        label_a = "finetuned_v2pro_e4" if fine_is_a else "zero_shot_v2pro"
        label_b = "zero_shot_v2pro" if fine_is_a else "finetuned_v2pro_e4"
        raw_a = raw_root / f"{pair_id}_A.wav"
        raw_b = raw_root / f"{pair_id}_B.wav"
        target_a = matched_root / f"{pair_id}_A.wav"
        target_b = matched_root / f"{pair_id}_B.wav"
        shutil.copy2(source_a, raw_a)
        shutil.copy2(source_b, raw_b)
        gain_db_a, gain_db_b = level_match_pair(source_a, source_b, target_a, target_b)
        key_rows.append(
            {
                "pair_id": pair_id,
                "source_sample_id": before["id"],
                "A": label_a,
                "B": label_b,
                "A_sha256": sha256(target_a),
                "B_sha256": sha256(target_b),
                "A_raw_sha256": sha256(raw_a),
                "B_raw_sha256": sha256(raw_b),
                "A_level_match_gain_db": gain_db_a,
                "B_level_match_gain_db": gain_db_b,
            }
        )
        review_rows.append(
            [pair_id, before["language"], before["text"], str(target_a), str(target_b), *([""] * 10)]
        )
    key_payload = {
        "build_id": BUILD_ID,
        "baseline_id": "baseline_001",
        "warning": "Keep this mapping hidden until the listening sheet is complete. Review paths use pair-level RMS-matched copies; raw blind copies are retained separately.",
        "seed": 3407,
        "pairs": key_rows,
    }
    (REPORT_ROOT / "tts_blind_ab_key.json").write_text(
        json.dumps(key_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    with review_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "pair_id",
                "language",
                "text",
                "A_path",
                "B_path",
                "preference_A_B_TIE",
                "A_pronunciation_1_5",
                "B_pronunciation_1_5",
                "A_voice_similarity_1_5",
                "B_voice_similarity_1_5",
                "A_naturalness_1_5",
                "B_naturalness_1_5",
                "A_severe_issue_Y_N",
                "B_severe_issue_Y_N",
                "notes",
            ]
        )
        writer.writerows(review_rows)
    return key_rows


def main() -> int:
    REPORT_ROOT.mkdir(parents=True, exist_ok=True)
    MODEL_ROOT.mkdir(parents=True, exist_ok=True)
    zero = json.loads((ZERO_ROOT / "metrics.json").read_text(encoding="utf-8"))
    fine = json.loads((FINE_ROOT / "metrics.json").read_text(encoding="utf-8"))
    zero["samples"] = [row for row in zero["samples"] if row["language"] == TARGET_LANGUAGE]
    fine["samples"] = [row for row in fine["samples"] if row["language"] == TARGET_LANGUAGE]
    if [row["id"] for row in zero["samples"]] != [row["id"] for row in fine["samples"]]:
        raise RuntimeError("zero-shot and fine-tuned evaluation IDs differ")
    zero_wavs = validate_wavs(ZERO_ROOT, len(zero["samples"]), prefix="jp_")
    fine_wavs = validate_wavs(FINE_ROOT, len(fine["samples"]), prefix="jp_")
    blind_key = build_blind_pack(zero, fine)

    final_gpt = MODEL_ROOT / "checkpoints" / "gpt_weights" / "meguri_baseline_001-e4.ckpt"
    final_sovits = MODEL_ROOT / "checkpoints" / "sovits_weights" / "meguri_baseline_001_e4_s1312.pth"
    required_existing = [
        final_gpt,
        final_sovits,
        MODEL_ROOT / "training_s1.yaml",
        MODEL_ROOT / "training_s2.json",
        MODEL_ROOT / "tts_infer_v2pro_finetuned.yaml",
        MODEL_ROOT / "dataset_build_id.txt",
        MODEL_ROOT / "dataset_checksums.txt",
        FINE_ROOT / "metrics.json",
    ]
    if not all(path.is_file() for path in required_existing):
        missing = [str(path) for path in required_existing if not path.is_file()]
        raise RuntimeError(f"missing baseline artifacts: {missing}")

    zero_stats = warm_stats(zero)
    fine_stats = warm_stats(fine)
    zero_acoustic = acoustic_summary(zero_wavs)
    fine_acoustic = acoustic_summary(fine_wavs)
    dependency_manifest = ROOT / "reports" / "model_dependency_checksums.sha256"
    formal_dataset_manifest = ROOT / "datasets" / "meguri" / "checksums.sha256"
    comparison = {
        "build_id": BUILD_ID,
        "baseline_id": "baseline_001",
        "status": "SMALL_BASELINE_COMPLETE",
        "release_status": "NOT_RELEASED",
        "full_training_decision": "HOLD_PENDING_BLIND_AB_LISTENING_AND_USER_APPROVAL",
        "text_lora_decision": "DO_NOT_START",
        "training": {
            "framework": "GPT-SoVITS v2Pro",
            "framework_commit": FRAMEWORK_COMMIT,
            "seed": 3407,
            "input_train_rows": 1306,
            "gpt_effective_rows_after_framework_filter": 1275,
            "gpt_epochs": 4,
            "sovits_rows": 1306,
            "sovits_epochs": 4,
            "batch_size": 4,
            "precision": "fp16",
            "test_rows_used_for_training": 0,
            "successful_gpt_resume_wall_seconds": 101.6,
            "successful_sovits_wall_seconds": 734.8,
            "observed_nvidia_smi_peak_memory_mib": 10351,
        },
        "evaluation": {
            "fixed_samples": len(fine["samples"]),
            "languages": {"ja": len(fine["samples"])},
            "active_language_scope": [TARGET_LANGUAGE],
            "excluded_historical_languages": ["zh"],
            "zero_shot_warm": zero_stats,
            "fine_tuned_warm": fine_stats,
            "zero_shot_acoustic": zero_acoustic,
            "fine_tuned_acoustic": fine_acoustic,
            "zero_shot_max_peak_gpu_allocated_mib": zero["max_peak_gpu_allocated_mib"],
            "zero_shot_max_peak_gpu_reserved_mib": zero["max_peak_gpu_reserved_mib"],
            "fine_tuned_max_peak_gpu_allocated_mib": fine["max_peak_gpu_allocated_mib"],
            "fine_tuned_max_peak_gpu_reserved_mib": fine["max_peak_gpu_reserved_mib"],
            "zero_shot_wavs": zero_wavs,
            "fine_tuned_wavs": fine_wavs,
            "blind_pairs": len(blind_key),
            "human_ab_complete": False,
            "quality_verdict_available": False,
        },
        "final_weights": {
            "gpt": {"path": str(final_gpt), "sha256": sha256(final_gpt), "bytes": final_gpt.stat().st_size},
            "sovits": {"path": str(final_sovits), "sha256": sha256(final_sovits), "bytes": final_sovits.stat().st_size},
        },
        "dependency_manifests": {
            "formal_dataset": {"path": str(formal_dataset_manifest), "sha256": sha256(formal_dataset_manifest)},
            "pretrained_models": {"path": str(dependency_manifest), "sha256": sha256(dependency_manifest)},
        },
        "constraints": {
            "tts_runtime_scope": "local desktop pet only",
            "astrbot_tts": False,
            "website_tts": False,
            "cloud_server_tts": False,
            "formal_dataset_modified": False,
            "weights_uploaded": False,
        },
        "generated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }

    training_config_yaml = f"""build_id: {BUILD_ID}
baseline_id: baseline_001
framework: GPT-SoVITS
framework_version: v2Pro
framework_commit: {FRAMEWORK_COMMIT}
seed: 3407
precision: fp16
batch_size: 4
gpt_epochs: 4
sovits_epochs: 4
training_manifest: D:/program/meguri-pet/training/tts_work/{BUILD_ID}/baseline_001/filelists/train.list
test_used_for_training: false
test_used_for_reference_selection: false
test_used_for_hyperparameter_tuning: false
gpt_config: training_s1.yaml
sovits_config: training_s2.json
inference_config: tts_infer_v2pro_finetuned.yaml
"""
    (MODEL_ROOT / "training_config.yaml").write_text(training_config_yaml, encoding="utf-8")
    (MODEL_ROOT / "framework_commit.txt").write_text(
        f"commit={FRAMEWORK_COMMIT}\ndescribe=5519185-dirty\ndirty_at_inventory=true\n", encoding="utf-8"
    )
    inventory = json.loads((REPORT_ROOT / "software_inventory.json").read_text(encoding="utf-8"))
    (MODEL_ROOT / "environment.json").write_text(
        json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (MODEL_ROOT / "metrics.json").write_text(
        json.dumps(comparison, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    model_card = f"""# Meguri GPT-SoVITS v2Pro small baseline

- Build: `{BUILD_ID}`
- Baseline: `baseline_001`
- Status: small baseline complete, not released; full training is held pending blind A/B listening and explicit user approval.
- Scope: local desktop pet TTS only. AstrBot, website, and cloud server must not load or run TTS.
- Training: GPT 4 epochs (1275 effective rows after framework filter), SoVITS 4 epochs (1306 rows), batch 4, FP16, seed 3407.
- Isolation: validation/test rows were not used for parameter updates; test rows used for training = 0.
- Reference: validation `MGR000238`, selected before fine-tuning; no test reference was used.
- Final GPT: `{final_gpt}`
- Final SoVITS: `{final_sovits}`
- Active fixed evaluation: {len(fine["samples"])} Japanese sentences, seed 3407. Chinese smoke-test artifacts are historical and excluded.
- Warm RTF: zero-shot mean `{zero_stats['mean_rtf']}`, fine-tuned mean `{fine_stats['mean_rtf']}`.
- Peak inference CUDA allocated: zero-shot `{zero['max_peak_gpu_allocated_mib']}` MiB, fine-tuned `{fine['max_peak_gpu_allocated_mib']}` MiB.
- Training peak observed through nvidia-smi: 10351 MiB.
- Required next gate: complete `reports/tts_finetune_ab_review.csv` without opening the blind key.
- Text policy: phase 1 remains closed LLM + Prompt + RAG; text LoRA was not started.

RTF and waveform statistics are operational checks, not an audio-quality verdict. See `checksums.sha256`, `metrics.json`, and the reports in the project root.
"""
    (MODEL_ROOT / "model_card.md").write_text(model_card, encoding="utf-8")
    (MODEL_ROOT / "MODEL_CARD.md").write_text(model_card, encoding="utf-8")

    checksum_files = [
        final_gpt,
        final_sovits,
        MODEL_ROOT / "training_config.yaml",
        MODEL_ROOT / "training_s1.yaml",
        MODEL_ROOT / "training_s2.json",
        MODEL_ROOT / "tts_infer_v2pro_finetuned.yaml",
        MODEL_ROOT / "dataset_build_id.txt",
        MODEL_ROOT / "dataset_checksums.txt",
        MODEL_ROOT / "framework_commit.txt",
        MODEL_ROOT / "environment.json",
        MODEL_ROOT / "metrics.json",
        MODEL_ROOT / "model_card.md",
        FINE_ROOT / "metrics.json",
    ] + sorted(FINE_ROOT.glob("*.wav"))
    checksum_lines = [f"{sha256(path)} *{path.relative_to(MODEL_ROOT).as_posix()}" for path in checksum_files]
    (MODEL_ROOT / "checksums.sha256").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")

    (REPORT_ROOT / "tts_baseline_training_report.json").write_text(
        json.dumps(comparison, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    baseline_report = f"""# TTS 小规模基线训练报告

结论：`SMALL_BASELINE_COMPLETE`，但尚未发布。GPT 4 epoch 与 SoVITS 4 epoch 已完成，当前 10 条日语微调评测音频全部为单声道 32 kHz PCM，可正常解码。中文历史冒烟样本不再参与开发。

- 数据：训练清单 1306 条；GPT 内置音素/秒过滤后实际 1275 条；SoVITS 1306 条；测试集参与训练 0 条。
- 参数：v2Pro、batch 4、FP16、seed 3407、GPT 4 epoch、SoVITS 4 epoch。
- 热启动 RTF：零样本 {zero_stats['mean_rtf']}，微调 {fine_stats['mean_rtf']}。
- 推理峰值 CUDA allocated：零样本 {zero['max_peak_gpu_allocated_mib']} MiB，微调 {fine['max_peak_gpu_allocated_mib']} MiB。
- 训练峰值显存：nvidia-smi 观察值 10351 MiB。
- 最终 GPT SHA-256：`{sha256(final_gpt)}`
- 最终 SoVITS SHA-256：`{sha256(final_sovits)}`
- 人工门禁：填写 `reports/tts_finetune_ab_review.csv`；评分前不要打开 `reports/tts_blind_ab_key.json`。
- 运行边界：仅本地桌宠运行 TTS；AstrBot、网站、云服务器均不运行 TTS。
- 文本路线：继续闭源 LLM + Prompt + RAG；未启动文本 LoRA。

首次零样本前端初始化按依赖默认行为获取约 22.6 MB 的日语 OpenJTalk 字典；没有新增或下载大模型。
"""
    (REPORT_ROOT / "tts_baseline_training_report.md").write_text(baseline_report, encoding="utf-8")

    zero_report = f"""# GPT-SoVITS 零样本基线评估

- 模型：本地既有 v2Pro 预训练 GPT `s1v3.ckpt` + SoVITS `s2Gv2Pro.pth`。
- 参考音频：validation `MGR000238`，7.226644 秒，neutral；未使用 test 音频。
- 固定句：日语 10 条；seed 3407；全部生成并通过 32 kHz 单声道 PCM 解码校验。
- 热启动 RTF：均值 {zero_stats['mean_rtf']}，中位数 {zero_stats['median_rtf']}，最大值 {zero_stats['max_rtf']}。
- 峰值 CUDA allocated/reserved：{zero['max_peak_gpu_allocated_mib']} / {zero['max_peak_gpu_reserved_mib']} MiB。
- 波形自动统计：平均 peak {zero_acoustic['mean_peak_dbfs']} dBFS，平均 RMS {zero_acoustic['mean_rms_dbfs']} dBFS，最大 clipping ratio {zero_acoustic['max_clipping_sample_ratio']}。

音色相似度、日语自然度、停顿、情绪稳定性、杂音、参考内容泄漏和跨语言音色保持均需人工盲听；自动指标不替代这些结论。盲测清单见 `reports/tts_finetune_ab_review.csv`。
"""
    (REPORT_ROOT / "zero_shot_evaluation.md").write_text(zero_report, encoding="utf-8")

    comparison_report = f"""# TTS 模型对比报告

| 指标 | 零样本 v2Pro | 小规模微调 e4 |
|---|---:|---:|
| 日语固定样本数 | 10 | 10 |
| 热启动平均 RTF | {zero_stats['mean_rtf']} | {fine_stats['mean_rtf']} |
| 热启动中位 RTF | {zero_stats['median_rtf']} | {fine_stats['median_rtf']} |
| 峰值 CUDA allocated (MiB) | {zero['max_peak_gpu_allocated_mib']} | {fine['max_peak_gpu_allocated_mib']} |
| 峰值 CUDA reserved (MiB) | {zero['max_peak_gpu_reserved_mib']} | {fine['max_peak_gpu_reserved_mib']} |
| 平均输出时长 (s) | {zero_acoustic['mean_duration_seconds']} | {fine_acoustic['mean_duration_seconds']} |
| 平均 peak (dBFS) | {zero_acoustic['mean_peak_dbfs']} | {fine_acoustic['mean_peak_dbfs']} |
| 平均 RMS (dBFS) | {zero_acoustic['mean_rms_dbfs']} | {fine_acoustic['mean_rms_dbfs']} |
| 最大 clipping ratio | {zero_acoustic['max_clipping_sample_ratio']} | {fine_acoustic['max_clipping_sample_ratio']} |

自动结论：两者均可实时推理并通过格式、解码和削波检查；当前正式结论只使用 10 条日语盲测。微调原始输出平均 RMS 较高，因此默认盲测包逐对仅衰减较响一侧至相同 RMS，原始盲测副本另行保留。

发布/完整训练结论：`HOLD_PENDING_BLIND_AB_LISTENING_AND_USER_APPROVAL`。
"""
    (REPORT_ROOT / "tts_model_comparison.md").write_text(comparison_report, encoding="utf-8")

    release_decision = {
        "build_id": BUILD_ID,
        "baseline_id": "baseline_001",
        "small_baseline_complete": True,
        "release_approved": False,
        "full_training_approved": False,
        "decision": "HOLD_PENDING_BLIND_AB_LISTENING_AND_USER_APPROVAL",
        "automatic_checks": {
            "weights_load": "PASS",
            "fixed_inference_ja_10_of_10": "PASS",
            "wav_decode_ja_10_of_10": "PASS",
            "test_rows_used_for_training": 0,
            "formal_dataset_checksums": "PASS",
        },
        "human_blind_review": {"required_pairs": len(fine["samples"]), "completed_pairs": 0, "status": "PENDING", "evaluated_languages": [TARGET_LANGUAGE]},
        "go_rule": {
            "required_pairs": len(fine["samples"]),
            "finetuned_wins_at_least": 8,
            "win_margin_at_least": 4,
            "mean_score_delta_at_least": 0.25,
            "finetuned_exclusive_severe_issues": 0,
            "explicit_user_approval_required": True
        },
        "next_action": "Complete reports/tts_finetune_ab_review.csv without opening reports/tts_blind_ab_key.json.",
    }
    (REPORT_ROOT / "tts_release_decision.json").write_text(
        json.dumps(release_decision, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    known_failures = """# TTS 已知问题与未决项

1. 日语盲测尚未评分，因此没有音色/自然度发布结论，也不能批准完整训练。
2. 日语前端在 `jp_001` 的分句日志中产生 `おかえりなさい、。` 片段，需重点试听停顿与标点自然度。
3. GPT 数据加载器按音素/秒阈值过滤了 31 条，GPT 实际训练 1275 条；SoVITS 仍使用 1306 条。
4. 当前基线只训练单一稳定音色，没有分别训练八种 voice_style；voice_style 仍是运行时有限枚举与后续控制接口。
5. 中文 3 条仅用于跨语言冒烟测试，不能证明完整中文自然度。
6. 微调推理权重加载时会报告训练专用 `enc_q` 键缺失；这是 GPT-SoVITS 导出推理权重的预期结构，13/13 推理已成功，但升级框架后需重新验证。
7. GPT-SoVITS 仓库在训练前已是 dirty 状态；本任务未修改或重置该仓库，commit 与 dirty 状态已写入模型目录。
8. 微调原始输出平均 RMS 比零样本高约 3.7 dB；人工质量盲测使用逐对 RMS 等响副本，原始副本仅用于检查真实输出电平。
"""
    (REPORT_ROOT / "tts_known_failures.md").write_text(known_failures, encoding="utf-8")

    print(json.dumps(comparison, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
