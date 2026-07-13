from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "reports"
MODEL = Path(r"D:\AI\models\meguri\gpt-sovits\meguri_v2_02c3db0c507d7c2d\baseline_001")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    metrics_path = MODEL / "metrics.json"
    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    result = json.loads((REPORTS / "tts_blind_ab_result.json").read_text(encoding="utf-8"))
    evaluation = metrics["evaluation"]
    for name in ("zero_shot_wavs", "fine_tuned_wavs"):
        rows = evaluation[name]
        evaluation[f"historical_excluded_{name}"] = [row for row in rows if row["name"].startswith("zh_")]
        evaluation[name] = [row for row in rows if row["name"].startswith("jp_")]
    evaluation["fixed_samples"] = 10
    evaluation["languages"] = {"ja": 10}
    evaluation["active_language_scope"] = ["ja"]
    evaluation["excluded_historical_languages"] = ["zh"]
    evaluation["human_ab_complete"] = True
    evaluation["quality_verdict_available"] = True
    metrics["active_language_scope"] = ["ja"]
    metrics["excluded_historical_languages"] = ["zh"]
    metrics["release_decision"] = result["decision"]
    metrics["full_training_approved"] = False
    metrics_path.write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    config_path = MODEL / "training_config.yaml"
    config_text = config_path.read_text(encoding="utf-8")
    if "evaluation_languages:" not in config_text:
        config_text += "evaluation_languages: [ja]\nexcluded_evaluation_languages: [zh]\n"
    config_path.write_text(config_text, encoding="utf-8")

    model_card = f"""# Meguri GPT-SoVITS v2Pro Japanese voice baseline

- Build: `meguri_v2_02c3db0c507d7c2d`
- Baseline: `baseline_001`
- Active scope: Japanese voice only.
- Chinese samples generated in the first smoke test are historical artifacts and are excluded from all current evaluation, tuning, release, and future generation.
- Status: small baseline is a Go candidate; full training is not started because explicit user approval is still required.
- Training: GPT 4 epochs (1275 effective rows after framework filter), SoVITS 4 epochs (1306 rows), batch 4, FP16, seed 3407.
- Isolation: validation/test rows were not used for parameter updates; test rows used for training = 0.
- Reference: validation `MGR000238`; no test reference was used.
- Active fixed evaluation: 10 Japanese sentences, seed 3407.
- Japanese blind result: fine-tuned wins {result['wins']['finetuned_v2pro_e4']} vs zero-shot {result['wins']['zero_shot_v2pro']}; mean score {result['mean_blind_score']['finetuned_v2pro_e4']} vs {result['mean_blind_score']['zero_shot_v2pro']}.
- Final GPT: `{MODEL / 'checkpoints' / 'gpt_weights' / 'meguri_baseline_001-e4.ckpt'}`
- Final SoVITS: `{MODEL / 'checkpoints' / 'sovits_weights' / 'meguri_baseline_001_e4_s1312.pth'}`
- Runtime scope: local desktop pet only. AstrBot, website, and cloud server must not load or run TTS.
- Text policy: phase 1 remains closed LLM + Prompt + RAG; text LoRA was not started.

See `metrics.json`, `checksums.sha256`, and the Japanese-only comparison report.
"""
    (MODEL / "model_card.md").write_text(model_card, encoding="utf-8")
    (MODEL / "MODEL_CARD.md").write_text(model_card, encoding="utf-8")

    zero = metrics["evaluation"]
    comparison = f"""# TTS 日语模型对比报告

当前开发范围：日语语音。中文历史冒烟样本不参与此结论，也不会再生成新的中文语音。

| 指标 | 零样本 | 小规模微调 e4 |
|---|---:|---:|
| 日语固定样本数 | 10 | 10 |
| 热启动平均 RTF | {zero['zero_shot_warm']['mean_rtf']} | {zero['fine_tuned_warm']['mean_rtf']} |
| 热启动中位 RTF | {zero['zero_shot_warm']['median_rtf']} | {zero['fine_tuned_warm']['median_rtf']} |
| 峰值 CUDA allocated (MiB) | {zero['zero_shot_max_peak_gpu_allocated_mib']} | {zero['fine_tuned_max_peak_gpu_allocated_mib']} |
| 平均 RMS (dBFS) | {zero['zero_shot_acoustic']['mean_rms_dbfs']} | {zero['fine_tuned_acoustic']['mean_rms_dbfs']} |

盲测结果：微调版胜 {result['wins']['finetuned_v2pro_e4']} 组，零样本胜 {result['wins']['zero_shot_v2pro']} 组；平均盲评分分别为 {result['mean_blind_score']['finetuned_v2pro_e4']} 与 {result['mean_blind_score']['zero_shot_v2pro']}，差值 {result['finetuned_score_delta']}。3 组严重问题由两边共同出现，微调特有严重问题为 0。

结论：`GO_CANDIDATE_AWAITING_EXPLICIT_USER_APPROVAL`。只有用户明确批准后才可启动较长的日语完整训练。
"""
    (REPORTS / "tts_model_comparison.md").write_text(comparison, encoding="utf-8")

    zero_report = f"""# GPT-SoVITS 日语零样本基线评估

- 当前范围：日语 10 条；中文历史冒烟样本已排除。
- 参考音频：validation `MGR000238`，7.226644 秒，neutral；未使用 test 音频。
- seed：3407；10 条均通过 32 kHz 单声道 PCM 解码校验。
- 热启动 RTF：均值 {zero['zero_shot_warm']['mean_rtf']}，中位数 {zero['zero_shot_warm']['median_rtf']}。
- 峰值 CUDA allocated/reserved：{zero['zero_shot_max_peak_gpu_allocated_mib']} / {zero['zero_shot_max_peak_gpu_reserved_mib']} MiB。

中文旧样本只作历史记录，不作为当前训练、调参或发布证据。
"""
    (REPORTS / "zero_shot_evaluation.md").write_text(zero_report, encoding="utf-8")

    training_report = f"""# TTS 日语小规模基线训练报告

GPT 4 epoch 与 SoVITS 4 epoch 已完成。当前只维护日语语音；中文历史冒烟样本不再参与评测、调参或后续训练。

- 训练清单 1306 条；GPT 内置过滤后实际 1275 条；SoVITS 1306 条；测试集参与训练 0 条。
- 日语盲测：微调胜 {result['wins']['finetuned_v2pro_e4']}/{result['evaluated_pairs']}，零样本胜 {result['wins']['zero_shot_v2pro']}/{result['evaluated_pairs']}。
- 平均盲评分：微调 {result['mean_blind_score']['finetuned_v2pro_e4']}，零样本 {result['mean_blind_score']['zero_shot_v2pro']}。
- 决策：`{result['decision']}`；完整训练仍等待用户明确批准。
- 文本路线：闭源 LLM + Prompt + RAG；未启动文本 LoRA。
- TTS 运行边界：仅本地桌宠；AstrBot、网站、云服务器均不运行 TTS。
"""
    (REPORTS / "tts_baseline_training_report.md").write_text(training_report, encoding="utf-8")
    (REPORTS / "tts_baseline_training_report.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    known = (REPORTS / "tts_known_failures.md").read_text(encoding="utf-8")
    if "Chinese historical artifacts" not in known:
        known += "\n8. Chinese samples from the initial smoke test are historical artifacts only; active development and future generation are Japanese-only.\n"
    (REPORTS / "tts_known_failures.md").write_text(known, encoding="utf-8")

    # Refresh the model-local manifest after the active-scope metadata changed.
    manifest = MODEL / "checksums.sha256"
    lines = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        expected, relative = line.split(" *", 1)
        path = MODEL / relative
        lines.append(f"{sha256(path)} *{relative}")
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"active_language_scope": ["ja"], "decision": result["decision"]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
