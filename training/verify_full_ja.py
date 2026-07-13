from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BUILD_ID = "meguri_v2_02c3db0c507d7c2d"
MODEL = Path(r"D:\AI\models\meguri\gpt-sovits\meguri_v2_02c3db0c507d7c2d\full_ja_001")
WORK = ROOT / "training" / "tts_work" / BUILD_ID / "full_ja_001"
REPORTS = ROOT / "reports"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    checks: list[dict[str, Any]] = []

    def record(name: str, passed: bool, evidence: Any) -> None:
        checks.append({"name": name, "passed": bool(passed), "evidence": evidence})

    required = ["model_card.md", "training_config.yaml", "training_s1.yaml", "training_s2.json", "dataset_build_id.txt", "dataset_checksums.txt", "framework_commit.txt", "environment.json", "metrics.json", "checksums.sha256", "checkpoints", "samples", "logs"]
    missing = [name for name in required if not (MODEL / name).exists()]
    record("model_directory_contract", not missing, {"missing": missing})

    config = json.loads((ROOT / "configs" / "tts_full_ja.json").read_text(encoding="utf-8"))
    eval_config = json.loads((ROOT / "configs" / "tts_eval_sentences.json").read_text(encoding="utf-8"))
    record("japanese_only_config", config["active_language_scope"] == ["ja"] and len(eval_config["sentences"]) == 10 and all(row["language"] == "ja" for row in eval_config["sentences"]), {"scope": config["active_language_scope"], "sentences": len(eval_config["sentences"])})
    record("epoch_policy", config["training_policy"]["gpt_epochs"] == 20 and config["training_policy"]["sovits_epochs"] == 20, config["training_policy"])

    gpt_weights = list((MODEL / "checkpoints" / "gpt_weights").glob("meguri_full_ja_001-e*.ckpt"))
    sovits_weights = list((MODEL / "checkpoints" / "sovits_weights").glob("meguri_full_ja_001_e*_s*.pth"))
    g_full = list((WORK / "logs_s2_v2Pro").glob("G_*.pth"))
    d_full = list((WORK / "logs_s2_v2Pro").glob("D_*.pth"))
    record("checkpoint_counts", len(gpt_weights) == 20 and len(sovits_weights) == 20 and len(g_full) == 20 and len(d_full) == 20, {"gpt_deploy": len(gpt_weights), "sovits_deploy": len(sovits_weights), "sovits_G": len(g_full), "sovits_D": len(d_full)})

    metrics = json.loads((MODEL / "metrics.json").read_text(encoding="utf-8"))
    final_gpt = Path(metrics["final_weights"]["gpt"]["path"])
    final_sovits = Path(metrics["final_weights"]["sovits"]["path"])
    hashes_ok = sha256(final_gpt) == metrics["final_weights"]["gpt"]["sha256"] and sha256(final_sovits) == metrics["final_weights"]["sovits"]["sha256"]
    record("final_weight_hashes", hashes_ok, {"gpt": metrics["final_weights"]["gpt"]["sha256"], "sovits": metrics["final_weights"]["sovits"]["sha256"]})

    wavs = sorted((MODEL / "samples").glob("*.wav"))
    sample_metrics = json.loads((MODEL / "samples" / "metrics.json").read_text(encoding="utf-8"))
    record("japanese_fixed_inference", len(wavs) == 10 and not list((MODEL / "samples").glob("zh_*.wav")) and all(row["language"] == "ja" for row in sample_metrics["samples"]), {"wav_count": len(wavs), "languages": sorted({row["language"] for row in sample_metrics["samples"]})})

    manifest_errors = []
    lines = (MODEL / "checksums.sha256").read_text(encoding="utf-8").splitlines()
    for line in lines:
        expected, relative = line.split(" *", 1)
        path = MODEL / relative
        if not path.is_file() or sha256(path) != expected:
            manifest_errors.append(relative)
    record("model_manifest", len(lines) >= 23 and not manifest_errors, {"verified": len(lines), "errors": manifest_errors})

    gpt_log = (MODEL / "logs" / "orchestrator" / "train_gpt.log").read_text(encoding="utf-8", errors="replace")
    sovits_log = (MODEL / "logs" / "orchestrator" / "train_sovits.log").read_text(encoding="utf-8", errors="replace")
    record("training_process_exit", "max_epochs=20" in gpt_log and "returncode=0" in gpt_log and "training done" in sovits_log and "returncode=0" in sovits_log, {"gpt_returncode_0": "returncode=0" in gpt_log, "sovits_returncode_0": "returncode=0" in sovits_log})

    split_rows = list(csv.DictReader((ROOT / "configs" / "tts_split_manifest.tsv").open("r", encoding="utf-8", newline=""), delimiter="\t"))
    test_ids = {row["voice_id"] for row in split_rows if row["split"] == "test"}
    train_list = WORK / "filelists" / "train.list"
    train_lines = [line for line in train_list.read_text(encoding="utf-8").splitlines() if line]
    trained_ids = {Path(line.split("|", 1)[0]).stem for line in train_lines}
    record("test_isolation", len(train_lines) == 1306 and not (trained_ids & test_ids) and all("|JP|" in line for line in train_lines), {"train_rows": len(train_lines), "test_overlap": sorted(trained_ids & test_ids), "language_marker": "JP"})

    verification = json.loads((REPORTS / "training_input_verification.json").read_text(encoding="utf-8"))
    record("formal_dataset_integrity", verification["decision"] == "GO" and verification["checksums"]["verified"] is True and not verification["checksums"]["mismatches"], {"decision": verification["decision"], "checksum_entries": verification["checksums"]["entry_count"]})

    contract = json.loads((ROOT / "configs" / "local_tts_contract.json").read_text(encoding="utf-8"))
    clients = contract["clients"]
    record("runtime_boundary", contract["scope"] == "local_desktop_pet_only" and clients["desktop_pet"]["enabled"] is True and all(clients[name]["enabled"] is False for name in ("astrbot", "website", "cloud_server")), clients)

    lora = (REPORTS / "text_lora_decision.md").read_text(encoding="utf-8")
    record("text_lora_not_started", "DO NOT START" in lora, "closed LLM + Prompt + RAG remains active")
    failed = [row["name"] for row in checks if not row["passed"]]
    payload = {"build_id": BUILD_ID, "run_id": "full_ja_001", "status": "FULL_TRAINING_PASS_RELEASE_LISTENING_PENDING" if not failed else "FAIL", "training_goal_complete": not failed, "failed": failed, "checks": checks}
    (REPORTS / "full_ja_deliverables_verification.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown = ["# 日语完整训练交付验证", "", f"状态：`{payload['status']}`。", ""] + [f"- {'PASS' if row['passed'] else 'FAIL'} — {row['name']}" for row in checks] + [""]
    (REPORTS / "full_ja_deliverables_verification.md").write_text("\n".join(markdown), encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if not failed else 2


if __name__ == "__main__":
    raise SystemExit(main())
