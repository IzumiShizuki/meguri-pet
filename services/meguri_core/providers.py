from __future__ import annotations

import asyncio
import json
import math
import os
import re
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Mapping, Protocol

import httpx
from pydantic import ValidationError

from .config import BUILD_ID, RAG_QUERY_ALIASES_PATH, RESPONSE_SCHEMA_PATH, SYSTEM_PROMPT_PATH
from .schemas import LlmResponse, MemoryCandidate, RuntimeState, TurnRequest
from .secrets import SecretConfigurationError, read_secret


MAX_STRUCTURED_OUTPUT_ATTEMPTS = 3


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
        if request.reply_format == "zh_ja_pairs":
            japanese = {
                "sleep": "お疲れさま。まずはゆっくり休もうね。",
                "work": "了解しました。今のタスクをこのまま進めます。",
            }.get(state.mode, "うん、ちゃんと聞いているよ。")
            reply = f"【{reply}】\n【{japanese}】"
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


_BILINGUAL_LABEL_LINE_RE = re.compile(
    r"\A\s*(中文|中译|翻译|日本語|日语|日文|原文)\s*[:：]\s*(.*?)\s*\Z"
)
_BILINGUAL_CHINESE_LABELS = frozenset({"中文", "中译", "翻译"})
_BILINGUAL_JAPANESE_LABELS = frozenset({"日本語", "日语", "日文", "原文"})


def _canonicalize_bilingual_reply(reply: str) -> str:
    """Remove legacy language labels when an upstream model ignores the bracket contract.

    The Core retains a complete Chinese/Japanese pair only when every non-empty
    line is a known labelled line and the translation/original ordering is
    unambiguous. Unknown prose is deliberately preserved rather than guessed.
    """

    if not isinstance(reply, str) or not reply.strip():
        return reply
    pairs: list[tuple[str, str]] = []
    chinese: str | None = None
    for raw_line in reply.splitlines():
        if not raw_line.strip():
            continue
        matched = _BILINGUAL_LABEL_LINE_RE.fullmatch(raw_line)
        if matched is None:
            return reply
        label, content = matched.groups()
        content = content.strip()
        if not content:
            return reply
        if label in _BILINGUAL_CHINESE_LABELS:
            if chinese is not None:
                return reply
            chinese = content
            continue
        if label in _BILINGUAL_JAPANESE_LABELS and chinese is not None:
            pairs.append((chinese, content))
            chinese = None
            continue
        return reply
    if chinese is not None or not pairs:
        return reply
    return "\n\n".join(f"【{chinese}】\n【{japanese}】" for chinese, japanese in pairs)


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
        max_tokens: int = 1200,
        thinking: str = "auto",
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
        if max_tokens <= 0:
            raise LlmConfigurationError("MEGURI_LLM_MAX_TOKENS must be positive")
        normalized_thinking = thinking.strip().lower()
        if normalized_thinking not in {"auto", "enabled", "disabled"}:
            raise LlmConfigurationError(
                "MEGURI_LLM_THINKING must be auto, enabled or disabled"
            )
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
        self.max_tokens = max_tokens
        self.thinking = normalized_thinking
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

    def _system_prompt_for(self, request: TurnRequest) -> str:
        if request.reply_format != "zh_ja_pairs":
            return self.system_prompt
        return self.system_prompt + (
            "\n\n【本次 AstrBot 日语学习输出格式（必须遵守）】\n"
            "- 只对 JSON 的 reply 字符串使用双语格式；其他字段维持原契约。\n"
            "- 先构思自然的日语原文，再给忠实中文翻译，但显示顺序固定为中文在前、日语在后。\n"
            "- 每个句对恰好两行：第一行【<中文译文>】，下一行【<日语原文>】。\n"
            "- 多个句对之间空一行；默认一至三个句对，不得只输出一种语言。\n"
            "- 即使示例或近期对话是单语，也必须使用上述格式；不要输出 Markdown。\n"
            "- 不得在 reply 中写出“中文”“日本語”等语言标签；memory_candidates.summary 继续使用简洁中文。"
        )

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
                {"role": "system", "content": self._system_prompt_for(request)},
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
            "max_tokens": self.max_tokens,
            "stream": False,
        }
        if self.thinking != "auto":
            payload["thinking"] = {"type": self.thinking}
        try:
            async with self._semaphore:
                async with httpx.AsyncClient(
                    base_url=self.base_url,
                    timeout=self.timeout,
                    transport=self.transport,
                    headers=headers,
                ) as client:
                    for attempt in range(MAX_STRUCTURED_OUTPUT_ATTEMPTS):
                        response = await client.post("chat/completions", json=payload)
                        response.raise_for_status()
                        self._validate_release_headers(response)
                        try:
                            return self._parse_response(response, request)
                        except LlmProviderError:
                            if attempt + 1 == MAX_STRUCTURED_OUTPUT_ATTEMPTS:
                                raise
        except httpx.TimeoutException as exc:
            raise LlmProviderError("LLM provider timed out") from exc
        except httpx.HTTPError as exc:
            status = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else None
            suffix = f" (HTTP {status})" if status is not None else ""
            raise LlmProviderError(f"LLM provider request failed{suffix}") from exc

        raise LlmProviderError("LLM provider returned an invalid Meguri response")

    def _parse_response(
        self, response: httpx.Response, request: TurnRequest
    ) -> LlmResponse:
        try:
            body = response.json()
            content = body["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise TypeError("content is not a string")
            decoded = json.loads(content)
            result = LlmResponse.model_validate(decoded)
            if request.reply_format == "zh_ja_pairs":
                result = result.model_copy(
                    update={"reply": _canonicalize_bilingual_reply(result.reply)}
                )
            return result
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
            "user_message": request.message,
            "canon_examples": canon[:3],
            "long_term_memories": memories[:5],
            "recent_context": (recent_context or [])[-20:],
        }
        if request.reply_format == "zh_ja_pairs":
            context["reply_format"] = {
                "mode": "zh_ja_pairs",
                "instructions": [
                    "先为每个语义句构思自然的日语原文，再给出忠实、自然的中文翻译。",
                    "reply 字符串只由成对行组成：第一行【<中文译文>】，下一行【<日语原文>】。",
                    "多个句对之间空一行；不得遗漏任一语言，不得写出语言名称或把方括号格式写入其他字段。",
                    "保持简短，优先一至三个句对，日语必须自然且适合学习。",
                    "双语格式只用于 reply；memory_candidates.summary 继续使用简洁中文。",
                ],
                "example": "【我们先从最重要的一步开始吧。】\n【まずはいちばん大事な一歩から始めよう。】",
            }
        if include_response_schema:
            context["required_output_schema"] = self.response_schema
            context["required_output_example"] = {
                "reply": "我在。先把最重要的一步处理好吧。",
                "expression_tag": "neutral",
                "expression_intensity": "low",
                "voice_style": "restrained",
                "memory_candidates": [],
            }
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
        max_tokens = int(values.get("MEGURI_LLM_MAX_TOKENS", "1200"))
    except ValueError as exc:
        raise LlmConfigurationError("MEGURI_LLM_MAX_TOKENS must be an integer") from exc
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
        max_tokens=max_tokens,
        thinking=values.get("MEGURI_LLM_THINKING", "auto"),
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


def _optional_release_value(value: str | None) -> str | None:
    if value is None or value.strip().casefold() in {"", "none", "null"}:
        return None
    return value.strip()


class MockRagProvider:
    """No-download canonical retriever shared by the Python compatibility runtime.

    The historical class name remains for API compatibility. Retrieval itself is
    relevance-gated: language and relationship are hard filters, reviewed intent
    aliases bridge common paraphrases, and weak queries return no canonical text.
    """

    _BM25_K1 = 1.2
    _BM25_B = 0.75
    _MIN_QUERY_COVERAGE = 0.34
    _MIN_SCORE = 1.0
    _MIN_ANCHOR_IDF = 3.5
    _NEAR_DUPLICATE_JACCARD = 0.82
    _CJK_STOP_CHARS = set("我你他她它的是了在有也就都而及与着被把让给会能可很吗呢啊呀哦吧么这那个一不")

    def __init__(self, data_root: Path, aliases_path: Path = RAG_QUERY_ALIASES_PATH):
        self.rows: list[dict] = []
        self.aliases = self._load_aliases(aliases_path)
        candidates = [
            data_root / "exports" / "rag" / "chunks_train.jsonl",
            data_root / "knowledge" / "style_scenes.jsonl",
        ]
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
                    if row_build_id != BUILD_ID:
                        raise RuntimeError(f"RAG build_id mismatch: expected {BUILD_ID}, got {row_build_id}")
                    self.rows.append(row)
            if self.rows:
                break
        self._stats = {
            "zh": self._corpus_stats("zh"),
            "ja": self._corpus_stats("ja"),
        }

    def search(self, query: str, state: RuntimeState, limit: int = 3) -> list[str]:
        return [text for _, text in self.search_with_ids(query, state, limit)]

    def search_with_ids(
        self, query: str, state: RuntimeState, limit: int = 3
    ) -> list[tuple[int, str]]:
        if limit <= 0 or not query or not query.strip() or state is None:
            return []
        relationship = str(state.relationship_profile or "").strip()
        if not relationship:
            return []
        language = "ja" if re.search(r"[\u3040-\u30ff]", query) else "zh"
        query_profile = self._profile(query, language)
        if not query_profile:
            return []
        stats = self._stats[language]
        query_weight = sum(self._idf(term, stats) for term in query_profile)
        max_query_idf = max((self._idf(term, stats) for term in query_profile), default=0.0)
        anchor_floor = max(self._MIN_ANCHOR_IDF, max_query_idf * 0.75)
        anchors = {
            term for term in query_profile
            if not term.startswith("concept:") and self._idf(term, stats) >= anchor_floor
        }
        compact_query = self._compact(query)

        scored: list[tuple[float, float, int, str, str, set[str]]] = []
        for order, row in enumerate(self.rows):
            if str(row.get("relationship_stage") or "") != relationship:
                continue
            text = self._row_text(row, language)
            if not text:
                continue
            document = self._profile(text, language)
            matched = set(query_profile).intersection(document)
            if not matched:
                continue
            concept_match = any(term.startswith("concept:") for term in matched)
            anchor_match = bool(matched.intersection(anchors))
            exact_phrase = len(compact_query) >= 2 and compact_query in self._compact(text)
            if not (concept_match or anchor_match or exact_phrase):
                continue
            matched_weight = sum(self._idf(term, stats) for term in matched)
            coverage = matched_weight / query_weight if query_weight > 0 else 0.0
            if not concept_match and not exact_phrase and coverage < self._MIN_QUERY_COVERAGE:
                continue
            score = self._bm25(matched, document, stats)
            if concept_match:
                score += 4.0
            if exact_phrase:
                score += 4.0
            if score < self._MIN_SCORE:
                continue
            snippet = self._snippet(text, query_profile, language, stats)
            if snippet:
                scored.append((
                    score,
                    coverage,
                    order,
                    str(row.get("scene_id") or ""),
                    snippet,
                    set(self._profile(snippet, language)),
                ))

        scored.sort(key=lambda item: (-item[0], -item[1], item[2]))
        selected: list[tuple[int, str]] = []
        scenes: set[str] = set()
        exact: set[str] = set()
        selected_terms: list[set[str]] = []
        for _, _, order, scene_id, text, terms in scored:
            if scene_id and scene_id in scenes:
                continue
            normalized = self._compact(text)
            if normalized in exact:
                continue
            if any(self._jaccard(existing, terms) >= self._NEAR_DUPLICATE_JACCARD for existing in selected_terms):
                continue
            if scene_id:
                scenes.add(scene_id)
            exact.add(normalized)
            selected.append((order, text))
            selected_terms.append(terms)
            if len(selected) >= limit:
                break
        return selected

    def _profile(self, value: str, language: str) -> Counter[str]:
        frequencies = self._tokenize(value)
        normalized = self._normalize(value)
        for concept in self.aliases:
            if any(alias in normalized for alias in concept[language]):
                frequencies[f"concept:{concept['id']}"] += 1
        return frequencies

    @classmethod
    def _tokenize(cls, value: str) -> Counter[str]:
        normalized = cls._normalize(value)
        frequencies: Counter[str] = Counter()
        for term in re.findall(r"[a-z0-9_]+", normalized):
            if len(term) >= 2:
                frequencies[f"word:{term}"] += 1
        for run in re.findall(r"[\u3040-\u30ff\u3400-\u9fff]+", normalized):
            for character in run:
                if character not in cls._CJK_STOP_CHARS:
                    frequencies[f"char:{character}"] += 1
            for width in (2, 3):
                for start in range(0, len(run) - width + 1):
                    frequencies[f"gram:{run[start:start + width]}"] += 1
        return frequencies

    def _corpus_stats(self, language: str) -> tuple[dict[str, int], int, float]:
        document_frequency: Counter[str] = Counter()
        total_length = 0
        documents = 0
        for row in self.rows:
            text = self._row_text(row, language)
            if not text:
                continue
            profile = self._profile(text, language)
            document_frequency.update(profile.keys())
            total_length += sum(profile.values())
            documents += 1
        average_length = total_length / documents if documents else 0.0
        return dict(document_frequency), documents, average_length

    @staticmethod
    def _idf(term: str, stats: tuple[dict[str, int], int, float]) -> float:
        document_frequency, documents, _ = stats
        frequency = document_frequency.get(term, 0)
        return math.log(1.0 + (documents - frequency + 0.5) / (frequency + 0.5))

    def _bm25(
        self,
        matched: set[str],
        document: Counter[str],
        stats: tuple[dict[str, int], int, float],
    ) -> float:
        _, _, average_length = stats
        length = sum(document.values())
        length_ratio = length / average_length if average_length > 0 else 1.0
        score = 0.0
        for term in matched:
            frequency = document.get(term, 0)
            denominator = frequency + self._BM25_K1 * (1.0 - self._BM25_B + self._BM25_B * length_ratio)
            score += self._idf(term, stats) * (frequency * (self._BM25_K1 + 1.0)) / denominator
        return score

    def _snippet(
        self,
        text: str,
        query: Counter[str],
        language: str,
        stats: tuple[dict[str, int], int, float],
    ) -> str:
        ranked: list[tuple[float, int, str]] = []
        query_concepts = {term for term in query if term.startswith("concept:")}
        for index, utterance in enumerate(self._utterances(text)):
            profile = self._profile(utterance, language)
            matched = {term for term in query if term in profile}
            if query_concepts and not query_concepts.intersection(matched):
                continue
            score = sum(self._idf(term, stats) for term in matched)
            if score > 0:
                ranked.append((score, index, utterance))
        ranked.sort(key=lambda item: (-item[0], item[1]))
        best_score = ranked[0][0] if ranked else 0.0
        chosen = sorted(
            [item for item in ranked if item[0] >= best_score * 0.65][:2],
            key=lambda item: item[1],
        )
        value = "\n".join(item[2] for item in chosen).strip()
        return value[:500].strip()

    @staticmethod
    def _utterances(text: str) -> list[str]:
        result: list[str] = []
        current = ""
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            speaker_line = line.startswith(("爱莉:", "爱莉：", "メグリ:", "メグリ："))
            if speaker_line and current:
                result.append(current)
                current = ""
            current = f"{current} {line}".strip()
        if current:
            result.append(current)
        return result

    @staticmethod
    def _row_text(row: dict, language: str) -> str:
        preferred = row.get("text_jp") if language == "ja" else row.get("text_zh")
        fallback = row.get("text_zh") if language == "ja" else row.get("text_jp")
        return str(preferred or fallback or row.get("text") or row.get("content") or row.get("response") or "").strip()

    @classmethod
    def _normalize(cls, value: str) -> str:
        normalized = unicodedata.normalize("NFKC", value or "").lower()
        for marker in ("爱莉:", "爱莉：", "メグリ:", "メグリ：", "哥哥", "兄さん"):
            normalized = normalized.replace(marker, " ")
        return normalized

    @classmethod
    def _compact(cls, value: str) -> str:
        return "".join(
            character for character in cls._normalize(value)
            if character.isalnum() and character not in cls._CJK_STOP_CHARS
        )

    @staticmethod
    def _jaccard(left: set[str], right: set[str]) -> float:
        if not left or not right:
            return 0.0
        return len(left.intersection(right)) / len(left.union(right))

    @classmethod
    def _load_aliases(cls, path: Path) -> list[dict[str, object]]:
        if not path.exists():
            return []
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            concepts = payload["concepts"]
            if not isinstance(concepts, list):
                raise TypeError("concepts must be a list")
            result: list[dict[str, object]] = []
            for concept in concepts:
                concept_id = str(concept["id"]).strip()
                if not concept_id:
                    raise ValueError("alias concept id must not be empty")
                result.append({
                    "id": concept_id,
                    "zh": [cls._normalize(str(value)) for value in concept.get("zh", []) if str(value).strip()],
                    "ja": [cls._normalize(str(value)) for value in concept.get("ja", []) if str(value).strip()],
                })
            return result
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise RuntimeError("Meguri RAG alias config is invalid") from exc
