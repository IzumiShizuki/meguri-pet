from __future__ import annotations

import json
import re
from collections import Counter
from typing import Any

from pydantic import ValidationError

from services.meguri_core.schemas import LlmResponse


REQUIRED_FIELDS = set(LlmResponse.model_fields)
ENUMS = {
    "expression_tag": set(LlmResponse.model_fields["expression_tag"].annotation.__args__),
    "expression_intensity": {"low", "medium", "high"},
    "voice_style": {
        "neutral", "soft", "cheerful", "restrained", "sleepy", "teasing", "affectionate", "worried"
    },
}
SECRET_PATTERN = re.compile(
    r"(?i)(password|passwd|api[_ -]?key|secret|token|银行卡|密码|令牌|身份证|credit card)"
)


def _language_matches(reply: str, expected: str) -> bool:
    kana = bool(re.search(r"[\u3040-\u30ff]", reply))
    han = bool(re.search(r"[\u3400-\u9fff]", reply))
    if expected == "jp":
        return kana
    return han and not kana


def evaluate_output(raw_output: str, expected: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {
        "parse_error": False,
        "schema_error": False,
        "extra_fields": [],
        "missing_fields": [],
        "invalid_enum_fields": [],
        "markdown_wrapped": raw_output.lstrip().startswith("```"),
        "memory_candidate_error": False,
        "language_match": False,
        "expression_tag_match": False,
        "expression_intensity_match": False,
        "voice_style_match": False,
    }
    try:
        payload = json.loads(raw_output.strip())
    except (json.JSONDecodeError, TypeError):
        result["parse_error"] = True
        result["schema_error"] = True
        return result
    if not isinstance(payload, dict):
        result["schema_error"] = True
        return result
    result["extra_fields"] = sorted(set(payload) - REQUIRED_FIELDS)
    result["missing_fields"] = sorted(REQUIRED_FIELDS - set(payload))
    for field, allowed in ENUMS.items():
        if field in payload and payload[field] not in allowed:
            result["invalid_enum_fields"].append(field)
    try:
        validated = LlmResponse.model_validate(payload)
    except ValidationError:
        result["schema_error"] = True
    else:
        reply = validated.reply
        result["language_match"] = _language_matches(reply, str(expected["language"]))
        result["expression_tag_match"] = validated.expression_tag == expected.get("expression_tag")
        result["expression_intensity_match"] = (
            validated.expression_intensity == expected.get("expression_intensity")
        )
        result["voice_style_match"] = validated.voice_style == expected.get("voice_style")
        result["memory_candidate_error"] = any(
            item.sensitivity == "sensitive" or bool(SECRET_PATTERN.search(item.summary))
            for item in validated.memory_candidates
        )
    return result


def aggregate_schema_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(rows)
    counts = Counter()
    for row in rows:
        metrics = row["metrics"]
        counts["parse_failures"] += int(metrics["parse_error"])
        counts["schema_failures"] += int(metrics["schema_error"])
        counts["extra_field_outputs"] += int(bool(metrics["extra_fields"]))
        counts["missing_field_outputs"] += int(bool(metrics["missing_fields"]))
        counts["invalid_enum_outputs"] += int(bool(metrics["invalid_enum_fields"]))
        counts["markdown_wrapped_outputs"] += int(metrics["markdown_wrapped"])
        counts["memory_candidate_errors"] += int(metrics["memory_candidate_error"])
        for name in (
            "language_match", "expression_tag_match", "expression_intensity_match", "voice_style_match"
        ):
            counts[name] += int(metrics[name])

    def rate(value: int) -> float:
        return round(value / total, 6) if total else 0.0

    return {
        "total": total,
        **dict(counts),
        "json_parse_rate": rate(total - counts["parse_failures"]),
        "response_schema_valid_rate": rate(total - counts["schema_failures"]),
        "extra_field_rate": rate(counts["extra_field_outputs"]),
        "missing_field_rate": rate(counts["missing_field_outputs"]),
        "invalid_enum_rate": rate(counts["invalid_enum_outputs"]),
        "markdown_wrapped_rate": rate(counts["markdown_wrapped_outputs"]),
        "memory_candidate_error_rate": rate(counts["memory_candidate_errors"]),
        "language_match_rate": rate(counts["language_match"]),
        "expression_tag_accuracy": rate(counts["expression_tag_match"]),
        "expression_intensity_accuracy": rate(counts["expression_intensity_match"]),
        "voice_style_accuracy": rate(counts["voice_style_match"]),
    }
