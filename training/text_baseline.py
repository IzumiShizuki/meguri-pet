from __future__ import annotations

import argparse
import json
import math
import os
import re
import time
import urllib.error
import urllib.request
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from training.common import (
    BASELINE_ROOT,
    BUILD_ID,
    CONFIG_ROOT,
    DATASET_ROOT,
    REPORT_ROOT,
    collapse_text,
    ensure_output_dirs,
    read_json,
    read_jsonl,
    utc_now,
    write_json,
    write_jsonl,
)


EXPRESSION_TAGS = {
    "affectionate", "angry", "confused", "embarrassed", "excited", "happy",
    "neutral", "sad", "sleepy", "surprised", "teasing", "worried",
}
INTENSITIES = {"low", "medium", "high"}
VOICE_STYLES = {
    "neutral", "soft", "cheerful", "restrained", "sleepy", "teasing",
    "affectionate", "worried",
}
VARIANTS = {
    "A": {"rag": False, "runtime_state": False, "memory_mock": False},
    "B": {"rag": True, "runtime_state": False, "memory_mock": False},
    "C": {"rag": True, "runtime_state": True, "memory_mock": False},
    "D": {"rag": True, "runtime_state": True, "memory_mock": True},
}


def ngrams(text: str, size: int = 2) -> set[str]:
    normalized = re.sub(r"\s+", "", text.lower())
    if len(normalized) < size:
        return {normalized} if normalized else set()
    return {normalized[index:index + size] for index in range(len(normalized) - size + 1)}


def jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


@dataclass
class Retriever:
    chunks: list[dict[str, Any]]

    def search(self, query: str, language: str, relationship: str, top_k: int) -> list[dict[str, Any]]:
        query_terms = ngrams(query)
        scored: list[tuple[float, str, dict[str, Any]]] = []
        text_key = "text_jp" if language == "jp" else "text_zh"
        for chunk in self.chunks:
            if relationship and chunk.get("relationship_stage") != relationship:
                relationship_bonus = 0.0
            else:
                relationship_bonus = 0.08
            score = jaccard(query_terms, ngrams(str(chunk.get(text_key) or ""))) + relationship_bonus
            scored.append((score, str(chunk.get("chunk_id")), chunk))
        scored.sort(key=lambda item: (-item[0], item[1]))
        return [item[2] for item in scored[:top_k]]


def validate_response(value: Any) -> list[str]:
    errors: list[str] = []
    required = {"reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates"}
    if not isinstance(value, dict):
        return ["response_not_object"]
    if set(value) != required:
        errors.append("object_keys_mismatch")
    if not isinstance(value.get("reply"), str) or not value.get("reply", "").strip():
        errors.append("reply_invalid")
    if value.get("expression_tag") not in EXPRESSION_TAGS:
        errors.append("expression_tag_invalid")
    if value.get("expression_intensity") not in INTENSITIES:
        errors.append("expression_intensity_invalid")
    if value.get("voice_style") not in VOICE_STYLES:
        errors.append("voice_style_invalid")
    memories = value.get("memory_candidates")
    if not isinstance(memories, list) or len(memories) > 3:
        errors.append("memory_candidates_invalid")
    return errors


class Provider:
    name = "base"

    def complete(self, messages: list[dict[str, str]], language: str) -> dict[str, Any]:
        raise NotImplementedError


class MockProvider(Provider):
    name = "mock"

    def complete(self, messages: list[dict[str, str]], language: str) -> dict[str, Any]:
        # This deliberately does not read the test target. It validates plumbing only.
        reply = (
            "わかりました、兄さん。無理をしないように、一緒に確認しましょう。"
            if language == "jp"
            else "我知道了，哥哥。别太勉强，我们一起确认吧。"
        )
        return {
            "reply": reply,
            "expression_tag": "neutral",
            "expression_intensity": "low",
            "voice_style": "neutral",
            "memory_candidates": [],
        }


class OpenAICompatibleProvider(Provider):
    name = "openai-compatible"

    def __init__(self, temperature: float, max_tokens: int) -> None:
        base_url = os.environ.get("MEGURI_LLM_BASE_URL", "").rstrip("/")
        self.api_key = os.environ.get("MEGURI_LLM_API_KEY", "")
        self.model = os.environ.get("MEGURI_LLM_MODEL", "")
        if not base_url or not self.api_key or not self.model:
            raise RuntimeError(
                "MEGURI_LLM_BASE_URL, MEGURI_LLM_API_KEY and MEGURI_LLM_MODEL are required"
            )
        self.url = base_url if base_url.endswith("/chat/completions") else base_url + "/chat/completions"
        self.temperature = temperature
        self.max_tokens = max_tokens

    def complete(self, messages: list[dict[str, str]], language: str) -> dict[str, Any]:
        payload = json.dumps(
            {
                "model": self.model,
                "messages": messages,
                "temperature": self.temperature,
                "max_tokens": self.max_tokens,
                "response_format": {"type": "json_object"},
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            self.url,
            data=payload,
            headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = json.loads(response.read().decode("utf-8"))
            content = body["choices"][0]["message"]["content"]
            return json.loads(content)
        except (urllib.error.URLError, KeyError, IndexError, json.JSONDecodeError) as exc:
            raise RuntimeError(f"provider request failed: {type(exc).__name__}: {exc}") from exc


def case_query(case: dict[str, Any], language: str) -> str:
    messages = case.get("messages") or []
    user_messages = [str(item.get("content") or "") for item in messages if item.get("role") == "user"]
    if user_messages:
        return user_messages[-1]
    context = case.get("context") or []
    return "\n".join(str(item.get("text") or "") for item in context)


def assemble_messages(
    system_prompt: str,
    case: dict[str, Any],
    language: str,
    variant: str,
    retrieved: list[dict[str, Any]],
) -> list[dict[str, str]]:
    metadata = case.get("metadata") or {}
    options = VARIANTS[variant]
    dynamic: list[str] = []
    if options["runtime_state"]:
        dynamic.append(
            "<RUNTIME_STATE>\n"
            f"client_id: desktop_pet\nmode: private\n"
            f"relationship_profile: {metadata.get('relationship_stage', 'pursuit')}\n"
            f"outfit_code: {metadata.get('outfit_code', '03')}\n"
            "voice_enabled: true\n"
            f"allowed_expression_tags: {sorted(EXPRESSION_TAGS)}\n"
            "</RUNTIME_STATE>"
        )
    if options["rag"]:
        key = "text_jp" if language == "jp" else "text_zh"
        examples = "\n\n".join(
            f"[{chunk.get('chunk_id')}] {chunk.get(key, '')}" for chunk in retrieved
        )
        dynamic.append(f"<CANON_EXAMPLES>\n{examples}\n</CANON_EXAMPLES>")
    if options["memory_mock"]:
        dynamic.append(
            "<LONG_TERM_MEMORIES>\n"
            "- The user prefers concise, evidence-based technical updates.\n"
            "</LONG_TERM_MEMORIES>"
        )
    system = system_prompt + ("\n\n" + "\n\n".join(dynamic) if dynamic else "")
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": case_query(case, language)},
    ]


def evaluate_output(case: dict[str, Any], output: dict[str, Any], errors: list[str]) -> dict[str, Any]:
    target = case.get("target") or {}
    metadata = case.get("metadata") or {}
    target_reply = str(target.get("reply") or "")
    target_expression = str(metadata.get("expression_tag") or "")
    reply = str(output.get("reply") or "") if isinstance(output, dict) else ""
    return {
        "schema_valid": not errors,
        "reply_nonempty": bool(reply.strip()),
        "exact_original_reply": bool(target_reply) and collapse_text(reply) == collapse_text(target_reply),
        "expression_match": bool(target_expression) and output.get("expression_tag") == target_expression,
        "memory_limit_valid": isinstance(output.get("memory_candidates"), list)
        and len(output.get("memory_candidates", [])) <= 3,
    }


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(rows)
    metric_names = [
        "schema_valid", "reply_nonempty", "exact_original_reply", "expression_match", "memory_limit_valid"
    ]
    return {
        "cases": count,
        **{
            name + "_rate": round(
                sum(1 for row in rows if row["metrics"].get(name)) / count, 6
            ) if count else 0.0
            for name in metric_names
        },
        "average_latency_ms": round(
            sum(float(row.get("latency_ms") or 0) for row in rows) / count, 3
        ) if count else 0.0,
    }


def run(provider: Provider, languages: list[str], variants: list[str], limit: int | None) -> dict[str, Any]:
    ensure_output_dirs()
    config = read_json(CONFIG_ROOT / "text_baseline.json")
    system_prompt = (CONFIG_ROOT / "meguri_system_prompt.txt").read_text(encoding="utf-8")
    chunks = read_jsonl(DATASET_ROOT / "exports" / "rag" / "chunks_train.jsonl")
    retriever = Retriever(chunks)
    summary: dict[str, Any] = {
        "build_id": BUILD_ID,
        "generated_utc": utc_now(),
        "provider": provider.name,
        "mock_is_effect_evidence": False,
        "variants": {},
    }
    for variant in variants:
        summary["variants"][variant] = {}
        for language in languages:
            cases = read_jsonl(DATASET_ROOT / "exports" / "eval" / f"cases_{language}.jsonl")
            if limit:
                cases = cases[:limit]
            output_rows: list[dict[str, Any]] = []
            for case in cases:
                metadata = case.get("metadata") or {}
                query = case_query(case, language)
                retrieved = (
                    retriever.search(
                        query,
                        language,
                        str(metadata.get("relationship_stage") or ""),
                        int(config.get("retrieval_top_k") or 3),
                    )
                    if VARIANTS[variant]["rag"]
                    else []
                )
                messages = assemble_messages(system_prompt, case, language, variant, retrieved)
                started = time.perf_counter()
                try:
                    output = provider.complete(messages, language)
                    provider_error = ""
                except RuntimeError as exc:
                    output = {}
                    provider_error = str(exc)
                latency_ms = (time.perf_counter() - started) * 1000
                errors = validate_response(output)
                if provider_error:
                    errors.append("provider_error")
                output_rows.append(
                    {
                        "sample_id": case.get("sample_id"),
                        "language": language,
                        "variant": variant,
                        "provider": provider.name,
                        "retrieved_chunk_ids": [item.get("chunk_id") for item in retrieved],
                        "output": output,
                        "validation_errors": errors,
                        "provider_error": provider_error,
                        "latency_ms": round(latency_ms, 3),
                        "metrics": evaluate_output(case, output, errors),
                    }
                )
            target = BASELINE_ROOT / "text_prompt_rag" / provider.name / variant / f"cases_{language}.jsonl"
            write_jsonl(target, output_rows)
            summary["variants"][variant][language] = aggregate(output_rows)
            print(f"text baseline: provider={provider.name} variant={variant} language={language} cases={len(cases)}")
    return summary


def write_reports(summary: dict[str, Any]) -> None:
    write_json(REPORT_ROOT / "text_prompt_rag_baseline.json", summary)
    lines = [
        "# Text Prompt + RAG Baseline",
        "",
        f"- Build ID: `{summary['build_id']}`",
        f"- Provider: `{summary['provider']}`",
        f"- Generated UTC: `{summary['generated_utc']}`",
        "",
        "| Variant | Language | Cases | Schema | Expression match | Exact original reply |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for variant, languages in summary["variants"].items():
        for language, metrics in languages.items():
            lines.append(
                f"| {variant} | {language} | {metrics['cases']} | "
                f"{metrics['schema_valid_rate']:.3f} | {metrics['expression_match_rate']:.3f} | "
                f"{metrics['exact_original_reply_rate']:.3f} |"
            )
    lines.extend(
        [
            "",
            "Variants: A = system prompt only; B = prompt + RAG; C = prompt + RAG + runtime state; D = prompt + RAG + state + memory mock.",
            "",
            "Mock Provider validates retrieval, prompt assembly, JSON parsing, enum checks and report generation. It is not evidence of persona quality, safety quality or whether text LoRA is needed.",
            "",
            "Human or independent judge evaluation remains required for persona consistency, relationship-stage consistency, hallucination, safety boundary, language naturalness and original-line repetition tendency.",
        ]
    )
    (REPORT_ROOT / "text_prompt_rag_baseline.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    lora_lines = [
        "# Text LoRA Decision",
        "",
        "- Decision: **DO NOT START**",
        f"- Evidence provider: `{summary['provider']}`",
        "",
        "The first-stage architecture remains closed LLM + system prompt + RAG + runtime state + structured JSON. A Mock Provider run only proves that the evaluation harness works; it cannot demonstrate a quality gap.",
        "",
        "Text LoRA may be proposed only after a real approved provider completes the fixed evaluation, Prompt/RAG/state tuning on validation is exhausted, failures are shown to be unfixable at runtime, the base model and license are selected, and the user approves model download and training budget.",
    ]
    (REPORT_ROOT / "text_lora_decision.md").write_text("\n".join(lora_lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the fixed Meguri Prompt + RAG baseline")
    parser.add_argument("--provider", choices=["mock", "openai-compatible"], default="mock")
    parser.add_argument("--languages", nargs="+", choices=["jp", "zh"], default=["jp", "zh"])
    parser.add_argument("--variants", nargs="+", choices=sorted(VARIANTS), default=sorted(VARIANTS))
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    config = read_json(CONFIG_ROOT / "text_baseline.json")
    provider: Provider
    if args.provider == "mock":
        provider = MockProvider()
    else:
        provider = OpenAICompatibleProvider(
            float(config.get("temperature") or 0.2), int(config.get("max_output_tokens") or 500)
        )
    summary = run(provider, args.languages, args.variants, args.limit)
    write_reports(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
