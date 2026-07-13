from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Protocol

from .config import BUILD_ID
from .schemas import LlmResponse, MemoryCandidate, RuntimeState, TurnRequest


class LlmProvider(Protocol):
    def respond(
        self,
        request: TurnRequest,
        state: RuntimeState,
        canon: list[str],
        memories: list[str],
        recent_context: list[str] | None = None,
    ) -> LlmResponse: ...


class RagProvider(Protocol):
    def search(self, query: str, state: RuntimeState, limit: int = 3) -> list[str]: ...


class MockLLMProvider:
    """Deterministic provider used by local development and offline tests."""

    def respond(
        self,
        request: TurnRequest,
        state: RuntimeState,
        canon: list[str],
        memories: list[str],
        recent_context: list[str] | None = None,
    ) -> LlmResponse:
        message = request.message.strip()
        if state.mode == "sleep":
            reply = f"辛苦了。先慢慢休息一下吧：{message}"
            tag, intensity, voice = "sleepy", "low", "sleepy"
        elif state.mode == "work":
            reply = f"收到，我会按当前任务继续处理：{message}"
            tag, intensity, voice = "neutral", "low", "restrained"
        else:
            reply = f"嗯，我听到了：{message}"
            tag, intensity, voice = "gentle_happy", "medium", "soft"
        # The runtime schema deliberately rejects unknown tags and applies a deterministic fallback.
        if tag == "gentle_happy":
            tag = "happy"
        candidates: list[MemoryCandidate] = []
        if re.search(r"我喜欢|我不喜欢|我的项目|我叫", message):
            candidates.append(MemoryCandidate(type="preference", summary=message, confidence=0.8))
        return LlmResponse(reply=reply, expression_tag=tag, expression_intensity=intensity, voice_style=voice, memory_candidates=candidates)


class MockRagProvider:
    def __init__(self, data_root: Path):
        self.rows: list[dict] = []
        candidates = [data_root / "exports" / "rag" / "chunks_train.jsonl", data_root / "knowledge" / "style_scenes.jsonl"]
        for path in candidates:
            if not path.exists():
                continue
            for line in path.read_text(encoding="utf-8").splitlines():
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(row, dict):
                    row_build_id = row.get("build_id")
                    if row_build_id and row_build_id != BUILD_ID:
                        raise RuntimeError(f"RAG build_id mismatch: expected {BUILD_ID}, got {row_build_id}")
                    self.rows.append(row)
            if self.rows:
                break

    def search(self, query: str, state: RuntimeState, limit: int = 3) -> list[str]:
        terms = {x.lower() for x in re.findall(r"[\w\u4e00-\u9fff]+", query)}
        scored: list[tuple[int, str]] = []
        for row in self.rows:
            text = str(row.get("text_zh") or row.get("text_jp") or row.get("text") or row.get("content") or row.get("response") or "").strip()
            if not text:
                continue
            score = sum(1 for term in terms if term and term in text.lower())
            if row.get("relationship_stage") in {state.relationship_profile, None}:
                score += 1
            scored.append((score, text))
        scored.sort(key=lambda item: item[0], reverse=True)
        return [text for _, text in scored[:limit]]
