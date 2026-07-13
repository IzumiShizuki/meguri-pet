from __future__ import annotations

import csv
import hashlib
import json
import math
import wave
from array import array
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BUILD_ID = "meguri_v2_02c3db0c507d7c2d"
MODEL_ROOT = Path(r"D:\AI\models\meguri\gpt-sovits\meguri_v2_02c3db0c507d7c2d\baseline_001")
REPORT_ROOT = ROOT / "reports"


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

    required_reports = [
        "environment_report.md",
        "software_inventory.json",
        "model_dependency_checksums.sha256",
        "training_input_verification.md",
        "tts_acoustic_gate.json",
        "tts_manual_review.md",
        "zero_shot_evaluation.md",
        "tts_baseline_training_report.md",
        "tts_model_comparison.md",
        "tts_release_decision.json",
        "tts_known_failures.md",
        "text_prompt_rag_baseline.md",
        "text_lora_decision.md",
        "tts_finetune_ab_review.csv",
        "tts_blind_ab_key.json",
    ]
    missing_reports = [name for name in required_reports if not (REPORT_ROOT / name).is_file()]
    record("required_reports", not missing_reports, {"required": len(required_reports), "missing": missing_reports})

    required_model_entries = [
        "model_card.md",
        "training_config.yaml",
        "dataset_build_id.txt",
        "dataset_checksums.txt",
        "framework_commit.txt",
        "environment.json",
        "metrics.json",
        "checksums.sha256",
        "checkpoints",
        "samples",
        "logs",
    ]
    missing_model = [name for name in required_model_entries if not (MODEL_ROOT / name).exists()]
    record("model_directory_contract", not missing_model, {"required": required_model_entries, "missing": missing_model})

    build_id = (MODEL_ROOT / "dataset_build_id.txt").read_text(encoding="utf-8").strip()
    metrics = json.loads((MODEL_ROOT / "metrics.json").read_text(encoding="utf-8"))
    record("build_id", build_id == BUILD_ID and metrics.get("build_id") == BUILD_ID, {"file": build_id, "metrics": metrics.get("build_id")})

    checksum_errors: list[dict[str, str]] = []
    checksum_lines = (MODEL_ROOT / "checksums.sha256").read_text(encoding="utf-8").splitlines()
    for line in checksum_lines:
        expected, relative = line.split(" *", 1)
        path = MODEL_ROOT / Path(relative)
        if not path.is_file():
            checksum_errors.append({"path": relative, "error": "missing"})
        else:
            actual = sha256(path)
            if actual != expected:
                checksum_errors.append({"path": relative, "expected": expected, "actual": actual})
    record("model_checksums", len(checksum_lines) >= 20 and not checksum_errors, {"verified": len(checksum_lines), "errors": checksum_errors})

    formal_manifest = ROOT / "datasets" / "meguri" / "checksums.sha256"
    dependency_manifest = REPORT_ROOT / "model_dependency_checksums.sha256"
    deps = metrics["dependency_manifests"]
    dependency_ok = (
        sha256(formal_manifest) == deps["formal_dataset"]["sha256"]
        and sha256(dependency_manifest) == deps["pretrained_models"]["sha256"]
    )
    record("dependency_manifest_hashes", dependency_ok, deps)

    gate = json.loads((REPORT_ROOT / "tts_acoustic_gate.json").read_text(encoding="utf-8"))
    record(
        "acoustic_gate",
        gate.get("decision") in {"GO", "CONDITIONAL_GO"}
        and int(gate.get("manual_review_count", 0)) >= 100
        and bool(gate.get("manual_review_passed")),
        {"decision": gate.get("decision"), "manual_review_count": gate.get("manual_review_count"), "manual_review_passed": gate.get("manual_review_passed")},
    )

    split_rows = list(csv.DictReader((ROOT / "configs" / "tts_split_manifest.tsv").open("r", encoding="utf-8", newline=""), delimiter="\t"))
    test_ids = {row["voice_id"] for row in split_rows if row["split"] == "test"}
    train_list = ROOT / "training" / "tts_work" / BUILD_ID / "baseline_001" / "filelists" / "train.list"
    trained_ids = {Path(line.split("|", 1)[0]).stem for line in train_list.read_text(encoding="utf-8").splitlines() if line.strip()}
    overlap = sorted(test_ids & trained_ids)
    record("test_isolation", len(trained_ids) == 1306 and not overlap, {"trained": len(trained_ids), "test_ids": len(test_ids), "overlap": overlap})

    zero_samples = sorted((ROOT / "baselines" / "zero_shot" / "samples").glob("jp_*.wav"))
    fine_samples = sorted((MODEL_ROOT / "samples").glob("jp_*.wav"))
    active_eval = json.loads((ROOT / "configs" / "tts_eval_sentences.json").read_text(encoding="utf-8"))
    record(
        "fixed_inference_samples",
        len(zero_samples) == 10 and len(fine_samples) == 10 and all(item["language"] == "ja" for item in active_eval["sentences"]),
        {"zero_shot_ja": len(zero_samples), "fine_tuned_ja": len(fine_samples), "active_languages": sorted({item["language"] for item in active_eval["sentences"]})},
    )

    review_path = REPORT_ROOT / "tts_finetune_ab_review.csv"
    review_text = review_path.read_text(encoding="utf-8-sig")
    review_rows = [row for row in csv.DictReader(review_text.splitlines()) if row.get("language") == "ja"]
    key = json.loads((REPORT_ROOT / "tts_blind_ab_key.json").read_text(encoding="utf-8"))
    review_pair_ids = {row["pair_id"] for row in review_rows}
    active_key_pairs = [row for row in key["pairs"] if row["pair_id"] in review_pair_ids]
    a_labels = [row["A"] for row in active_key_pairs]
    def wav_rms(path: Path) -> float:
        with wave.open(str(path), "rb") as handle:
            samples = array("h", handle.readframes(handle.getnframes()))
        return math.sqrt(sum(value * value for value in samples) / len(samples))

    level_deltas = []
    for row in review_rows:
        rms_a = wav_rms(Path(row["A_path"]))
        rms_b = wav_rms(Path(row["B_path"]))
        level_deltas.append(abs(20 * math.log10(rms_a / rms_b)))
    blinded = (
        len(review_rows) == 10
        and "zero_shot" not in review_text
        and "finetuned" not in review_text
        and all(Path(row["A_path"]).is_file() and Path(row["B_path"]).is_file() for row in review_rows)
        and all("level_matched" in row["A_path"] and "level_matched" in row["B_path"] for row in review_rows)
        and max(level_deltas) <= 0.01
        and abs(a_labels.count("zero_shot_v2pro") - a_labels.count("finetuned_v2pro_e4")) <= 2
    )
    record(
        "blind_ab_package",
        blinded,
        {"pairs": len(review_rows), "A_zero_shot": a_labels.count("zero_shot_v2pro"), "A_finetuned": a_labels.count("finetuned_v2pro_e4"), "max_pair_rms_delta_db": round(max(level_deltas), 6)},
    )

    text_baseline = json.loads((REPORT_ROOT / "text_prompt_rag_baseline.json").read_text(encoding="utf-8"))
    variants_ok = set(text_baseline.get("variants", {})) == {"A", "B", "C", "D"}
    counts_ok = all(
        text_baseline["variants"][variant][language]["cases"] == 92
        for variant in "ABCD"
        for language in ("jp", "zh")
    )
    lora_text = (REPORT_ROOT / "text_lora_decision.md").read_text(encoding="utf-8")
    record(
        "text_prompt_rag_and_lora_policy",
        text_baseline.get("provider") == "mock"
        and text_baseline.get("mock_is_effect_evidence") is False
        and variants_ok
        and counts_ok
        and "DO NOT START" in lora_text,
        {"provider": text_baseline.get("provider"), "variants": sorted(text_baseline.get("variants", {})), "cases_each_language": 92},
    )

    contract_path = ROOT / "configs" / "local_tts_contract.json"
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    contract_text = contract_path.read_text(encoding="utf-8")
    clients = contract.get("clients", {})
    contract_ok = (
        contract.get("scope") == "local_desktop_pet_only"
        and clients.get("desktop_pet", {}).get("enabled") is True
        and all(clients.get(name, {}).get("enabled") is False for name in ("astrbot", "website", "cloud_server"))
        and "D:\\" not in contract_text
        and "pretrained_models" not in contract_text
        and contract["response"]["sample_rate_hz"] == 32000
        and set(contract["request"]["fields"]["voice_style"]["enum"])
        == {"neutral", "soft", "cheerful", "restrained", "sleepy", "teasing", "affectionate", "worried"}
    )
    record("local_tts_contract", contract_ok, {"scope": contract.get("scope"), "clients": clients})

    release = json.loads((REPORT_ROOT / "tts_release_decision.json").read_text(encoding="utf-8"))
    human_review = release["human_blind_review"]
    human_status_ok = human_review["status"] in {"PENDING", "COMPLETE"}
    ja_scope_ok = human_review.get("evaluated_languages", ["ja"]) == ["ja"]
    record(
        "approval_boundaries",
        release["release_approved"] is False
        and release["full_training_approved"] is False
        and metrics["text_lora_decision"] == "DO_NOT_START"
        and human_status_ok
        and ja_scope_ok,
        {"release": release["release_approved"], "full_training": release["full_training_approved"], "human_review": human_review["status"], "evaluated_languages": human_review.get("evaluated_languages")},
    )

    failed = [check for check in checks if not check["passed"]]
    payload = {
        "build_id": BUILD_ID,
        "status": "AUTOMATED_DELIVERABLES_PASS_USER_APPROVAL_PENDING" if not failed and release.get("decision", "").startswith("GO_CANDIDATE") else ("PASS" if not failed else "FAIL"),
        "checks": checks,
        "failed": [check["name"] for check in failed],
        "goal_complete": False,
        "remaining_gate": "explicit user approval for Japanese-only full training" if release.get("decision", "").startswith("GO_CANDIDATE") else "Japanese-only blind A/B review and release decision",
    }
    (REPORT_ROOT / "deliverables_verification.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown = [
        "# 训练交付完成性验证",
        "",
        f"状态：`{payload['status']}`。",
        "",
    ]
    for check in checks:
        markdown.append(f"- {'PASS' if check['passed'] else 'FAIL'} — {check['name']}")
    markdown.extend(["", f"剩余门禁：{payload['remaining_gate']}。", ""])
    (REPORT_ROOT / "deliverables_verification.md").write_text("\n".join(markdown), encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if not failed else 2


if __name__ == "__main__":
    raise SystemExit(main())
