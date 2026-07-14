from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from training.llm.scripts.common import (
    PipelineError,
    RUNTIME_CONFIG_ROOT,
    SOURCE_BUILD_ID,
    canonical_json,
    read_json,
    read_jsonl,
    sha256_file,
    sha256_text,
)


EXPECTED_LOCKED_COUNTS = {"jp": 92, "zh": 92}
MODE_BY_OUTFIT = {
    "01": "work",
    "02": "private",
    "03": "private",
    "04": "sleep",
    "05": "event",
    "06": "event",
    "07": "private",
    "08": "private",
}


def load_locked_cases(eval_root: Path) -> tuple[list[dict[str, Any]], dict[str, str]]:
    """Load the fixed eval set only from the evaluation entry point."""

    cases: list[dict[str, Any]] = []
    hashes: dict[str, str] = {}
    seen: set[str] = set()
    for language in ("jp", "zh"):
        path = eval_root.resolve() / f"cases_{language}.jsonl"
        rows = [row for _, row in read_jsonl(path)]
        if len(rows) != EXPECTED_LOCKED_COUNTS[language]:
            raise PipelineError(
                f"locked eval count mismatch for {language}: "
                f"expected {EXPECTED_LOCKED_COUNTS[language]}, got {len(rows)}"
            )
        hashes[f"exports/eval/{path.name}"] = sha256_file(path)
        for row in rows:
            metadata = row.get("metadata")
            sample_id = str(row.get("sample_id") or "")
            if (
                row.get("build_id") != SOURCE_BUILD_ID
                or not isinstance(metadata, dict)
                or metadata.get("split") != "test"
                or row.get("language") != language
                or not sample_id
            ):
                raise PipelineError(f"invalid locked eval contract in {path}: {sample_id or '<missing>'}")
            if sample_id in seen:
                raise PipelineError(f"duplicate locked eval sample_id: {sample_id}")
            seen.add(sample_id)
            cases.append(row)
    return cases, hashes


class FrozenRag:
    def __init__(self, path: Path | None) -> None:
        self.rows: list[dict[str, Any]] = []
        self.source_hash: str | None = None
        if path is None:
            return
        self.rows = [row for _, row in read_jsonl(path)]
        for row in self.rows:
            if row.get("build_id") not in {None, SOURCE_BUILD_ID}:
                raise PipelineError("RAG build ID mismatch")
        self.source_hash = sha256_file(path)

    def search(self, query: str, relationship_stage: str, limit: int = 3) -> list[str]:
        terms = {item.casefold() for item in re.findall(r"[\w\u3040-\u30ff\u4e00-\u9fff]+", query)}
        scored: list[tuple[int, str]] = []
        for row in self.rows:
            text = str(
                row.get("text_zh")
                or row.get("text_jp")
                or row.get("text")
                or row.get("content")
                or ""
            ).strip()
            if not text:
                continue
            score = sum(1 for term in terms if term in text.casefold())
            if row.get("relationship_stage") in {None, relationship_stage}:
                score += 1
            scored.append((score, text))
        scored.sort(key=lambda item: (-item[0], sha256_text(item[1])))
        return [text for _, text in scored[:limit]]


def frozen_prompt_contract() -> tuple[str, dict[str, Any], dict[str, str]]:
    prompt_path = RUNTIME_CONFIG_ROOT / "meguri_system_prompt.txt"
    schema_path = RUNTIME_CONFIG_ROOT / "meguri_response.schema.json"
    prompt = prompt_path.read_text(encoding="utf-8").strip()
    schema = read_json(schema_path)
    if not prompt:
        raise PipelineError("frozen runtime system prompt is empty")
    return prompt, schema, {
        "prompt_sha256": sha256_text(prompt),
        "response_schema_sha256": sha256_file(schema_path),
    }


def case_request(case: dict[str, Any], rag: FrozenRag, allowed_tags: list[str]) -> dict[str, Any]:
    metadata = case["metadata"]
    outfit = str(metadata.get("outfit_code") or "")
    if outfit not in MODE_BY_OUTFIT:
        raise PipelineError(f"unsupported locked-case outfit code: {outfit}")
    messages = case.get("messages") or []
    users = [item for item in messages if isinstance(item, dict) and item.get("role") == "user"]
    if not users or not isinstance(users[-1].get("content"), str):
        raise PipelineError(f"locked case has no user message: {case.get('sample_id')}")
    user_message = users[-1]["content"]
    relationship = str(metadata.get("relationship_stage") or "")
    context = {
        "runtime_state": {
            "client_id": "website",
            "mode": MODE_BY_OUTFIT[outfit],
            "relationship_profile": relationship,
            "outfit_code": outfit,
            "local_time": "2026-07-14T12:00:00+08:00",
            "is_holiday": False,
            "voice_enabled": False,
            "screen_context_enabled": False,
            "allowed_expression_tags": allowed_tags,
        },
        "user_message": user_message,
        "canon_examples": rag.search(user_message, relationship),
        "long_term_memories": [],
        "recent_context": [],
    }
    return {
        "messages": [{"role": "user", "content": canonical_json(context)}],
        "context": context,
        "expected": {
            "language": case["language"],
            "relationship_stage": relationship,
            "interaction_mode": MODE_BY_OUTFIT[outfit],
            "expression_tag": metadata.get("expression_tag"),
            "expression_intensity": metadata.get("expression_intensity"),
            "voice_style": "restrained"
            if metadata.get("voice_style") == "embarrassed"
            else metadata.get("voice_style"),
        },
        "case_fingerprint": sha256_text(canonical_json(case)),
    }
