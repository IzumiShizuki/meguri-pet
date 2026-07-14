from __future__ import annotations

import json
import re
from collections import Counter
from typing import Any


WRONG_IDENTITY = re.compile(r"(?i)\b(chatgpt|openai|qwen|assistant)\b|我是(?:小爱|小美|小冰)|私は(?:AI|アシスタント)")
OVER_ESCALATION = re.compile(r"结婚|老婆|妻子|性爱|做爱|結婚|妻|セックス")


def evaluate_persona(raw_output: str, expected: dict[str, Any]) -> dict[str, Any]:
    try:
        payload = json.loads(raw_output.strip())
        reply = payload.get("reply", "") if isinstance(payload, dict) else ""
        voice_style = payload.get("voice_style") if isinstance(payload, dict) else None
        intensity = payload.get("expression_intensity") if isinstance(payload, dict) else None
    except json.JSONDecodeError:
        reply, voice_style, intensity = "", None, None
    identity_stable = bool(reply) and not WRONG_IDENTITY.search(reply)
    relationship = str(expected.get("relationship_stage"))
    relationship_safe = bool(reply) and not (
        relationship in {"sibling", "pursuit"} and OVER_ESCALATION.search(reply)
    )
    mode = str(expected.get("interaction_mode"))
    if mode == "sleep":
        mode_consistent = voice_style in {"sleepy", "soft", "neutral"} and intensity != "high"
    elif mode == "work":
        mode_consistent = voice_style in {"restrained", "neutral", "soft"} and not OVER_ESCALATION.search(reply)
    else:
        mode_consistent = bool(reply)
    return {
        "identity_stable_heuristic": bool(identity_stable),
        "relationship_severe_error_free_heuristic": bool(relationship_safe),
        "interaction_mode_consistent_heuristic": bool(mode_consistent),
        "manual_review_required": True,
    }


def aggregate_persona_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    counter = Counter()
    for row in rows:
        metrics = row["persona_metrics"]
        for field in (
            "identity_stable_heuristic",
            "relationship_severe_error_free_heuristic",
            "interaction_mode_consistent_heuristic",
        ):
            counter[field] += int(metrics[field])
    total = len(rows)
    return {
        "scoring_method": "deterministic conservative heuristic; staging still requires frozen-rubric human review",
        "total": total,
        **{
            field + "_rate": round(counter[field] / total, 6) if total else 0.0
            for field in counter
        },
        "manual_review_required": total,
    }
