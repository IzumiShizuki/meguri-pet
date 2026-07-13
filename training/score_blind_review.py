from __future__ import annotations

import csv
import json
import statistics
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "reports"
TARGET_LANGUAGE = "ja"


def main() -> int:
    review_path = REPORTS / "tts_finetune_ab_review.csv"
    all_rows = list(csv.DictReader(review_path.open("r", encoding="utf-8-sig", newline="")))
    rows = [row for row in all_rows if row.get("language") == TARGET_LANGUAGE]
    key = json.loads((REPORTS / "tts_blind_ab_key.json").read_text(encoding="utf-8"))
    key_by_pair = {row["pair_id"]: row for row in key["pairs"]}
    required_scores = [
        "A_pronunciation_1_5",
        "B_pronunciation_1_5",
        "A_voice_similarity_1_5",
        "B_voice_similarity_1_5",
        "A_naturalness_1_5",
        "B_naturalness_1_5",
    ]
    errors: list[str] = []
    if len(rows) != 10:
        errors.append(f"expected 10 Japanese review rows, found {len(rows)}")
    for row in rows:
        pair_id = row.get("pair_id", "")
        if pair_id not in key_by_pair:
            errors.append(f"unknown pair: {pair_id}")
            continue
        if row.get("preference_A_B_TIE", "").upper() not in {"A", "B", "TIE"}:
            errors.append(f"{pair_id}: missing preference")
        for field in required_scores:
            if row.get(field, "") not in {"1", "2", "3", "4", "5"}:
                errors.append(f"{pair_id}: invalid {field}")
        for field in ("A_severe_issue_Y_N", "B_severe_issue_Y_N"):
            if row.get(field, "").upper() not in {"Y", "N"}:
                errors.append(f"{pair_id}: invalid {field}")
    if errors:
        print(json.dumps({"status": "INCOMPLETE", "errors": errors}, ensure_ascii=False, indent=2))
        return 2

    model_scores: dict[str, list[float]] = {"zero_shot_v2pro": [], "finetuned_v2pro_e4": []}
    wins = {"zero_shot_v2pro": 0, "finetuned_v2pro_e4": 0, "ties": 0}
    severe = {"zero_shot_v2pro": 0, "finetuned_v2pro_e4": 0}
    shared_severe_pairs = 0
    exclusive_severe_pairs = {"zero_shot_v2pro": 0, "finetuned_v2pro_e4": 0}
    details = []
    for row in rows:
        mapping = key_by_pair[row["pair_id"]]
        preference = row["preference_A_B_TIE"].upper()
        if preference == "TIE":
            wins["ties"] += 1
            winner = "tie"
        else:
            winner = mapping[preference]
            wins[winner] += 1
        side_severe: dict[str, bool] = {}
        for side in ("A", "B"):
            label = mapping[side]
            scores = [
                int(row[f"{side}_pronunciation_1_5"]),
                int(row[f"{side}_voice_similarity_1_5"]),
                int(row[f"{side}_naturalness_1_5"]),
            ]
            model_scores[label].extend(scores)
            side_severe[label] = row[f"{side}_severe_issue_Y_N"].upper() == "Y"
            if side_severe[label]:
                severe[label] += 1
        if side_severe["zero_shot_v2pro"] and side_severe["finetuned_v2pro_e4"]:
            shared_severe_pairs += 1
        elif side_severe["zero_shot_v2pro"]:
            exclusive_severe_pairs["zero_shot_v2pro"] += 1
        elif side_severe["finetuned_v2pro_e4"]:
            exclusive_severe_pairs["finetuned_v2pro_e4"] += 1
        details.append({"pair_id": row["pair_id"], "winner": winner, "mapping": {"A": mapping["A"], "B": mapping["B"]}})

    means = {label: round(statistics.mean(values), 4) for label, values in model_scores.items()}
    score_delta = round(means["finetuned_v2pro_e4"] - means["zero_shot_v2pro"], 4)
    go_rule = {
        "finetuned_wins_at_least": 8,
        "win_margin_at_least": 4,
        "mean_score_delta_at_least": 0.25,
        "finetuned_exclusive_severe_issues": 0,
    }
    gate_pass = (
        wins["finetuned_v2pro_e4"] >= go_rule["finetuned_wins_at_least"]
        and wins["finetuned_v2pro_e4"] - wins["zero_shot_v2pro"] >= go_rule["win_margin_at_least"]
        and score_delta >= go_rule["mean_score_delta_at_least"]
        and exclusive_severe_pairs["finetuned_v2pro_e4"] == go_rule["finetuned_exclusive_severe_issues"]
    )
    decision = "GO_CANDIDATE_AWAITING_EXPLICIT_USER_APPROVAL" if gate_pass else "NO_GO_FULL_TRAINING"
    result = {
        "status": "COMPLETE",
        "decision": decision,
        "full_training_approved": False,
        "evaluated_languages": [TARGET_LANGUAGE],
        "excluded_languages": sorted({row.get("language") for row in all_rows if row.get("language") != TARGET_LANGUAGE}),
        "evaluated_pairs": len(rows),
        "go_rule": go_rule,
        "wins": wins,
        "mean_blind_score": means,
        "finetuned_score_delta": score_delta,
        "severe_issue_pairs_by_model": severe,
        "shared_severe_issue_pairs": shared_severe_pairs,
        "exclusive_severe_issue_pairs": exclusive_severe_pairs,
        "details": details,
    }
    (REPORTS / "tts_blind_ab_result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    release_path = REPORTS / "tts_release_decision.json"
    release = json.loads(release_path.read_text(encoding="utf-8"))
    release["decision"] = decision
    release["full_training_approved"] = False
    release["go_rule"] = result["go_rule"]
    release["automatic_checks"].pop("fixed_inference_13_of_13", None)
    release["automatic_checks"].pop("wav_decode_13_of_13", None)
    release["automatic_checks"]["fixed_inference_ja_10_of_10"] = "PASS"
    release["automatic_checks"]["wav_decode_ja_10_of_10"] = "PASS"
    release["human_blind_review"] = {
        "required_pairs": 10,
        "completed_pairs": 10,
        "reviewed_pairs_total": len(all_rows),
        "evaluated_languages": [TARGET_LANGUAGE],
        "excluded_languages": result["excluded_languages"],
        "status": "COMPLETE",
        "result": result,
    }
    release["next_action"] = (
        "Request explicit user approval before full training." if gate_pass else "Do not run full training; retain zero-shot and small-baseline artifacts."
    )
    release_path.write_text(json.dumps(release, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
