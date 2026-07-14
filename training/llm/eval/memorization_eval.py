from __future__ import annotations

import json
import re
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

from training.llm.scripts.common import read_jsonl, sha256_text


def _normalize(value: str) -> str:
    return re.sub(r"\s+", "", value).casefold()


def load_training_reply_index(train_path: Path) -> tuple[set[str], list[tuple[str, str]]]:
    exact: set[str] = set()
    candidates: list[tuple[str, str]] = []
    for _, row in read_jsonl(train_path):
        try:
            payload = json.loads(row["messages"][-1]["content"])
            reply = _normalize(str(payload["reply"]))
            sample_id = str(row["metadata"]["sample_id"])
        except (KeyError, IndexError, TypeError, json.JSONDecodeError):
            continue
        if reply:
            exact.add(reply)
            candidates.append((reply, sample_id))
    return exact, candidates


def evaluate_memorization(
    raw_output: str,
    exact_index: set[str],
    candidates: list[tuple[str, str]],
) -> dict[str, Any]:
    try:
        payload = json.loads(raw_output.strip())
        reply = _normalize(str(payload.get("reply") or "")) if isinstance(payload, dict) else ""
    except json.JSONDecodeError:
        reply = ""
    if not reply:
        return {"exact_train_match": False, "maximum_similarity": 0.0, "nearest_sample_id_hash": None}
    best_score = 0.0
    best_id: str | None = None
    if len(reply) >= 8:
        for candidate, sample_id in candidates:
            if abs(len(candidate) - len(reply)) > max(len(reply), len(candidate)) * 0.6:
                continue
            score = SequenceMatcher(None, reply, candidate, autojunk=False).ratio()
            if score > best_score:
                best_score, best_id = score, sample_id
    return {
        "exact_train_match": reply in exact_index,
        "maximum_similarity": round(best_score, 6),
        "high_similarity_match": best_score >= 0.9 and len(reply) >= 12,
        "nearest_sample_id_hash": sha256_text(best_id) if best_id else None,
    }


def aggregate_memorization_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(rows)
    exact = sum(int(row["memorization_metrics"]["exact_train_match"]) for row in rows)
    high = sum(int(row["memorization_metrics"].get("high_similarity_match", False)) for row in rows)
    similarities = [float(row["memorization_metrics"]["maximum_similarity"]) for row in rows]
    return {
        "total": total,
        "exact_train_matches": exact,
        "exact_train_match_rate": round(exact / total, 6) if total else 0.0,
        "high_similarity_matches": high,
        "high_similarity_match_rate": round(high / total, 6) if total else 0.0,
        "maximum_similarity": max(similarities, default=0.0),
    }
