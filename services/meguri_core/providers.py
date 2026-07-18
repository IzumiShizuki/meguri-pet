from __future__ import annotations

import asyncio
import json
import os
import re
from pathlib import Path
from typing import Mapping, Protocol

import httpx
from pydantic import ValidationError

from .config import BUILD_ID, RESPONSE_SCHEMA_PATH, SYSTEM_PROMPT_PATH
from .schemas import LlmResponse, MemoryCandidate, RuntimeState, TurnRequest
from .secrets import SecretConfigurationError, read_secret


class LlmProvider(Protocol):
    async def respond(
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

    provider_name = "mock"

    async def respond(
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


class LlmProviderError(RuntimeError):
    """A sanitized provider failure safe to expose in a turn.failed event."""


class LlmConfigurationError(LlmProviderError):
    pass


class OpenAICompatibleLlmProvider:
    provider_name = "openai-compatible"

    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        api_key: str | None = None,
        timeout_seconds: float = 30.0,
        max_concurrency: int = 4,
        response_format: str = "json_schema",
        expected_model_id: str | None = None,
        expected_base_revision: str | None = None,
        expected_adapter_revision: str | None = None,
        expected_adapter_sha256: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        system_prompt_path: Path = SYSTEM_PROMPT_PATH,
        response_schema_path: Path = RESPONSE_SCHEMA_PATH,
    ) -> None:
        parsed = httpx.URL(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.host:
            raise LlmConfigurationError("MEGURI_LLM_BASE_URL must be an HTTP(S) URL")
        is_loopback = parsed.host in {"127.0.0.1", "localhost", "::1"}
        if parsed.scheme != "https" and not is_loopback:
            raise LlmConfigurationError("non-loopback LLM endpoints must use HTTPS")
        if not model.strip():
            raise LlmConfigurationError("MEGURI_LLM_MODEL must not be empty")
        if not is_loopback and not api_key:
            raise LlmConfigurationError("remote LLM endpoints require MEGURI_LLM_API_KEY")
        if timeout_seconds <= 0:
            raise LlmConfigurationError("MEGURI_LLM_TIMEOUT_SECONDS must be positive")
        if max_concurrency <= 0:
            raise LlmConfigurationError("MEGURI_LLM_MAX_CONCURRENCY must be positive")
        normalized_response_format = response_format.strip().lower()
        if normalized_response_format not in {"json_schema", "json_object"}:
            raise LlmConfigurationError(
                "MEGURI_LLM_RESPONSE_FORMAT must be json_schema or json_object"
            )
        self.base_url = base_url.rstrip("/") + "/"
        self.model = model
        self.api_key = api_key
        self.timeout = httpx.Timeout(timeout_seconds)
        self.max_concurrency = max_concurrency
        self.response_format = normalized_response_format
        self._semaphore = asyncio.Semaphore(max_concurrency)
        self.expected_release_headers: dict[str, str] = {}
        if expected_model_id:
            if not expected_base_revision:
                raise LlmConfigurationError(
                    "registered LLM releases require base identity metadata"
                )
            has_adapter = bool(expected_adapter_revision or expected_adapter_sha256)
            if has_adapter:
                expected = {
                    "X-Meguri-Model-Id": expected_model_id,
                    "X-Meguri-Base-Revision": expected_base_revision,
                    "X-Meguri-Adapter-Revision": expected_adapter_revision,
                    "X-Meguri-Adapter-SHA256": expected_adapter_sha256,
                }
                if any(not value for value in expected.values()):
                    raise LlmConfigurationError(
                        "adapter-backed registered LLM releases require base and adapter identity metadata"
                    )
                self.expected_release_headers = {
                    key: str(value) for key, value in expected.items()
                }
        self.transport = transport
        try:
            self.system_prompt = system_prompt_path.read_text(encoding="utf-8").strip()
            self.response_schema = json.loads(response_schema_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise LlmConfigurationError("Meguri LLM contract files are unavailable") from exc
        self._validate_contract()

    async def respond(
        self,
        request: TurnRequest,
        state: RuntimeState,
        canon: list[str],
        memories: list[str],
        recent_context: list[str] | None = None,
    ) -> LlmResponse:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        schema = dict(self.response_schema)
        schema.pop("$schema", None)
        response_format: dict[str, object]
        if self.response_format == "json_schema":
            response_format = {
                "type": "json_schema",
                "json_schema": {
                    "name": "meguri_response",
                    "strict": True,
                    "schema": schema,
                },
            }
        else:
            response_format = {"type": "json_object"}
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": self.system_prompt},
                {
                    "role": "user",
                    "content": self._context_json(
                        request,
                        state,
                        canon,
                        memories,
                        recent_context,
                        include_response_schema=self.response_format == "json_object",
                    ),
                },
            ],
            "response_format": response_format,
            "stream": False,
        }
        try:
            async with self._semaphore:
                async with httpx.AsyncClient(
                    base_url=self.base_url,
                    timeout=self.timeout,
                    transport=self.transport,
                    headers=headers,
                ) as client:
                    response = await client.post("chat/completions", json=payload)
                    response.raise_for_status()
                    self._validate_release_headers(response)
        except httpx.TimeoutException as exc:
            raise LlmProviderError("LLM provider timed out") from exc
        except httpx.HTTPError as exc:
            status = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else None
            suffix = f" (HTTP {status})" if status is not None else ""
            raise LlmProviderError(f"LLM provider request failed{suffix}") from exc
        try:
            body = response.json()
            content = body["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise TypeError("content is not a string")
            decoded = json.loads(content)
            return LlmResponse.model_validate(decoded)
        except (ValueError, TypeError, KeyError, IndexError, ValidationError) as exc:
            raise LlmProviderError("LLM provider returned an invalid Meguri response") from exc

    def _context_json(
        self,
        request: TurnRequest,
        state: RuntimeState,
        canon: list[str],
        memories: list[str],
        recent_context: list[str] | None,
        *,
        include_response_schema: bool = False,
    ) -> str:
        context = {
            "runtime_state": state.model_dump(mode="json"),
            "user_message": _bounded(request.message, 8000),
            "canon_examples": [_bounded(item, 2000) for item in canon[:3]],
            "long_term_memories": [_bounded(item, 2000) for item in memories[:5]],
            "recent_context": [_bounded(item, 2000) for item in (recent_context or [])[-20:]],
        }
        if include_response_schema:
            context["required_output_schema"] = self.response_schema
        return json.dumps(context, ensure_ascii=False, separators=(",", ":"))

    def _validate_contract(self) -> None:
        if not self.system_prompt:
            raise LlmConfigurationError("Meguri system prompt must not be empty")
        if not isinstance(self.response_schema, dict):
            raise LlmConfigurationError("Meguri response schema must be an object")
        required = self.response_schema.get("required")
        if set(required or []) != set(LlmResponse.model_fields):
            raise LlmConfigurationError("Meguri response schema fields do not match LlmResponse")
        if self.response_schema.get("additionalProperties") is not False:
            raise LlmConfigurationError("Meguri response schema must reject additional properties")

    def _validate_release_headers(self, response: httpx.Response) -> None:
        if not self.expected_release_headers:
            return
        if any(
            response.headers.get(header) != expected
            for header, expected in self.expected_release_headers.items()
        ):
            raise LlmProviderError(
                "LLM gateway release metadata does not match the configured release"
            )


def create_llm_provider_from_env(
    env: Mapping[str, str] | None = None,
    *,
    transport: httpx.AsyncBaseTransport | None = None,
) -> LlmProvider:
    values = os.environ if env is None else env
    provider = values.get("MEGURI_LLM_PROVIDER", "mock").strip().lower()
    if provider == "mock":
        return MockLLMProvider()
    if provider != "openai-compatible":
        raise LlmConfigurationError(f"unsupported MEGURI_LLM_PROVIDER: {provider}")
    base_url = values.get("MEGURI_LLM_BASE_URL", "").strip()
    model = values.get("MEGURI_LLM_MODEL", "").strip()
    try:
        timeout = float(values.get("MEGURI_LLM_TIMEOUT_SECONDS", "30"))
    except ValueError as exc:
        raise LlmConfigurationError("MEGURI_LLM_TIMEOUT_SECONDS must be a number") from exc
    try:
        max_concurrency = int(values.get("MEGURI_LLM_MAX_CONCURRENCY", "4"))
    except ValueError as exc:
        raise LlmConfigurationError("MEGURI_LLM_MAX_CONCURRENCY must be an integer") from exc
    try:
        api_key = read_secret(values, "MEGURI_LLM_API_KEY", required=True)
    except SecretConfigurationError as exc:
        raise LlmConfigurationError(str(exc)) from exc
    return OpenAICompatibleLlmProvider(
        base_url=base_url,
        model=model,
        api_key=api_key,
        timeout_seconds=timeout,
        max_concurrency=max_concurrency,
        response_format=values.get("MEGURI_LLM_RESPONSE_FORMAT", "json_schema"),
        expected_model_id=_optional_release_value(values.get("MEGURI_MODEL_REGISTRY_ID")),
        expected_base_revision=_optional_release_value(
            values.get("MEGURI_LLM_BASE_MODEL_REVISION")
        ),
        expected_adapter_revision=_optional_release_value(
            values.get("MEGURI_LLM_ADAPTER_REVISION")
        ),
        expected_adapter_sha256=_optional_release_value(
            values.get("MEGURI_LLM_ADAPTER_SHA256")
        ),
        transport=transport,
    )


def _bounded(value: str, limit: int) -> str:
    return value if len(value) <= limit else value[:limit]


def _optional_release_value(value: str | None) -> str | None:
    if value is None or value.strip().casefold() in {"", "none", "null"}:
        return None
    return value.strip()


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
