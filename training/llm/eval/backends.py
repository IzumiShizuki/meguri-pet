from __future__ import annotations

import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from training.llm.scripts.common import PipelineError, canonical_json, sha256_text
from training.llm.scripts.modeling import autocast_dtype, configure_compile_cache, load_base_model


@dataclass(frozen=True)
class GenerationResult:
    raw_output: str
    input_tokens: int | None
    first_token_latency_ms: float | None
    total_latency_ms: float
    generated_tokens: int | None
    tokens_per_second: float | None
    peak_vram_bytes: int | None


class EvaluationBackend(Protocol):
    metadata: dict[str, Any]

    def generate(
        self,
        system_prompt: str,
        user_content: str,
        cancel_event: Any | None = None,
    ) -> GenerationResult: ...


def complete_json_object_end(text: str) -> int | None:
    """Return the exclusive end of the first syntactically complete JSON object."""

    for start, initial in enumerate(text):
        if initial != "{":
            continue
        depth = 0
        in_string = False
        escaped = False
        for index in range(start, len(text)):
            character = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == '"':
                    in_string = False
                continue
            if character == '"':
                in_string = True
            elif character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth < 0:
                    break
                if depth == 0:
                    end = index + 1
                    try:
                        value = json.loads(text[start:end])
                    except json.JSONDecodeError:
                        break
                    if isinstance(value, dict):
                        return end
                    break
    return None


def validate_generation_controls(
    repetition_penalty: float,
    no_repeat_ngram_size: int,
) -> tuple[float, int]:
    if not 1.0 <= repetition_penalty <= 2.0:
        raise PipelineError("repetition penalty must be between 1.0 and 2.0")
    if not 0 <= no_repeat_ngram_size <= 32:
        raise PipelineError("no-repeat ngram size must be between 0 and 32")
    return float(repetition_penalty), int(no_repeat_ngram_size)


def json_object_start_token_id(tokenizer: Any) -> int:
    token_ids = tokenizer.encode("{", add_special_tokens=False)
    if len(token_ids) != 1:
        raise PipelineError("pinned tokenizer must encode the JSON object start as one token")
    return int(token_ids[0])


class OpenAIBackend:
    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        model_revision: str,
        tokenizer_revision: str,
        response_schema: dict[str, Any],
        api_key: str | None,
        timeout_seconds: float,
    ) -> None:
        configure_compile_cache()
        try:
            import httpx
        except ImportError as exc:
            raise PipelineError("httpx is required for OpenAI-compatible evaluation") from exc
        parsed = httpx.URL(base_url)
        loopback = parsed.host in {"127.0.0.1", "localhost", "::1"}
        if parsed.scheme not in {"http", "https"} or not parsed.host:
            raise PipelineError("evaluation endpoint must be HTTP(S)")
        if parsed.scheme != "https" and not loopback:
            raise PipelineError("non-loopback evaluation endpoints must use HTTPS")
        if not loopback and not api_key:
            raise PipelineError("remote evaluation endpoints require an API key")
        self._httpx = httpx
        self.base_url = base_url.rstrip("/") + "/"
        self.model = model
        self.response_schema = dict(response_schema)
        self.response_schema.pop("$schema", None)
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds
        self.metadata = {
            "backend": "openai-compatible",
            "model_repo_or_id": model,
            "model_revision": model_revision,
            "tokenizer_revision": tokenizer_revision,
            "chat_template_sha256": None,
        }

    def generate(
        self,
        system_prompt: str,
        user_content: str,
        cancel_event: Any | None = None,
    ) -> GenerationResult:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "meguri_response", "strict": True, "schema": self.response_schema},
            },
            "temperature": 0,
            "stream": False,
        }
        start = time.perf_counter()
        try:
            with self._httpx.Client(base_url=self.base_url, timeout=self.timeout_seconds, headers=headers) as client:
                response = client.post("chat/completions", json=body)
                response.raise_for_status()
                payload = response.json()
            content = payload["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise TypeError("provider content is not a string")
        except Exception as exc:
            raise PipelineError(f"OpenAI-compatible evaluation request failed: {type(exc).__name__}") from exc
        elapsed = (time.perf_counter() - start) * 1000
        usage = payload.get("usage") or {}
        tokens = usage.get("completion_tokens")
        prompt_tokens = usage.get("prompt_tokens")
        return GenerationResult(
            raw_output=content,
            input_tokens=int(prompt_tokens) if isinstance(prompt_tokens, int) else None,
            first_token_latency_ms=None,
            total_latency_ms=round(elapsed, 3),
            generated_tokens=int(tokens) if isinstance(tokens, int) else None,
            tokens_per_second=round(tokens / (elapsed / 1000), 3) if isinstance(tokens, int) and elapsed else None,
            peak_vram_bytes=None,
        )


class LocalUnslothBackend:
    def __init__(
        self,
        config: dict[str, Any],
        *,
        allow_download: bool,
        adapter_path: Path | None,
        max_new_tokens: int,
        input_pad_length: int | None = None,
        repetition_penalty: float = 1.0,
        no_repeat_ngram_size: int = 0,
        force_json_object_start: bool = False,
    ) -> None:
        try:
            import torch
        except ImportError as exc:
            raise PipelineError("PyTorch is required for local evaluation") from exc
        if not torch.cuda.is_available():
            raise PipelineError("CUDA is required for local model evaluation")
        model, processor, loader_class = load_base_model(config, allow_download=allow_download)
        if adapter_path is not None:
            try:
                from peft import PeftModel
            except ImportError as exc:
                raise PipelineError("PEFT is required to evaluate an adapter") from exc
            if not adapter_path.is_dir():
                raise PipelineError(f"adapter path does not exist: {adapter_path}")
            model = PeftModel.from_pretrained(model, str(adapter_path), is_trainable=False)
        if hasattr(loader_class, "for_inference"):
            loader_class.for_inference(model)
        model.eval()
        tokenizer = getattr(processor, "tokenizer", processor)
        template = getattr(tokenizer, "chat_template", None)
        if not isinstance(template, str) or not template:
            raise PipelineError("pinned tokenizer has no chat template")
        self.torch = torch
        self.model = model
        self.processor = processor
        self.tokenizer = tokenizer
        self.max_new_tokens = max_new_tokens
        self.repetition_penalty, self.no_repeat_ngram_size = validate_generation_controls(
            repetition_penalty,
            no_repeat_ngram_size,
        )
        self.force_json_object_start = bool(force_json_object_start)
        self.json_start_token_id = (
            json_object_start_token_id(tokenizer) if self.force_json_object_start else None
        )
        if input_pad_length is not None and input_pad_length <= 0:
            raise PipelineError("evaluation input pad length must be positive")
        if input_pad_length is not None and tokenizer.pad_token_id is None:
            raise PipelineError("pinned tokenizer has no pad token for fixed-shape evaluation")
        self.input_pad_length = input_pad_length
        self.autocast_dtype = autocast_dtype(config, torch)
        self.metadata = {
            "backend": "local-unsloth",
            "model_repo_or_id": config["model"]["repo_id"],
            "model_revision": config["model"]["revision"],
            "tokenizer_revision": config["model"]["tokenizer_revision"],
            "chat_template_sha256": sha256_text(template),
            "adapter_path": str(adapter_path.resolve()) if adapter_path else None,
            "input_pad_length": input_pad_length,
            "repetition_penalty": self.repetition_penalty,
            "no_repeat_ngram_size": self.no_repeat_ngram_size,
            "force_json_object_start": self.force_json_object_start,
        }

    def _inputs(self, system_prompt: str, user_content: str) -> tuple[dict[str, Any], int]:
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content},
        ]
        try:
            rendered = self.processor.apply_chat_template(
                messages,
                tokenize=False,
                add_generation_prompt=True,
                enable_thinking=False,
            )
        except Exception as exc:
            raise PipelineError("pinned chat template cannot render the frozen text request") from exc
        unpadded = self.tokenizer(rendered, return_tensors="pt", add_special_tokens=False)
        input_tokens = int(unpadded["input_ids"].shape[-1])
        if self.input_pad_length is not None:
            if input_tokens > self.input_pad_length:
                raise PipelineError(
                    f"evaluation input exceeds fixed pad length: {input_tokens}>{self.input_pad_length}"
                )
            self.tokenizer.padding_side = "left"
            inputs = self.tokenizer(
                rendered,
                return_tensors="pt",
                add_special_tokens=False,
                padding="max_length",
                max_length=self.input_pad_length,
                truncation=False,
            )
        else:
            inputs = unpadded
        return {key: value.to("cuda") for key, value in inputs.items()}, input_tokens

    def generate(
        self,
        system_prompt: str,
        user_content: str,
        cancel_event: Any | None = None,
    ) -> GenerationResult:
        torch = self.torch
        inputs, input_tokens = self._inputs(system_prompt, user_content)
        input_length = int(inputs["input_ids"].shape[-1])
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
        torch.cuda.synchronize()
        try:
            from transformers import (
                LogitsProcessor,
                LogitsProcessorList,
                StoppingCriteria,
                StoppingCriteriaList,
            )
        except ImportError as exc:
            raise PipelineError("Transformers generation controls are unavailable") from exc

        logits_processor = None
        if self.force_json_object_start:
            json_start_token = self.json_start_token_id

            class ForceJsonObjectStart(LogitsProcessor):
                def __call__(self, input_ids: Any, scores: Any) -> Any:
                    if int(input_ids.shape[-1]) == input_length:
                        allowed = scores[:, json_start_token].clone()
                        scores.fill_(float("-inf"))
                        scores[:, json_start_token] = allowed
                    return scores

            logits_processor = LogitsProcessorList([ForceJsonObjectStart()])
        first_start = time.perf_counter()
        with torch.inference_mode(), torch.autocast(
            device_type="cuda", dtype=self.autocast_dtype
        ):
            self.model.generate(
                **inputs,
                max_new_tokens=1,
                do_sample=False,
                use_cache=True,
                pad_token_id=self.tokenizer.eos_token_id,
                logits_processor=logits_processor,
            )
        torch.cuda.synchronize()
        first_ms = (time.perf_counter() - first_start) * 1000
        torch.cuda.synchronize()
        start = time.perf_counter()
        class JsonObjectComplete(StoppingCriteria):
            def __call__(self, input_ids: Any, scores: Any, **kwargs: Any) -> bool:
                generated_ids = input_ids[0][input_length:]
                text = self_tokenizer.decode(generated_ids, skip_special_tokens=True)
                return complete_json_object_end(text) is not None

        self_tokenizer = self.tokenizer
        criteria: list[Any] = [JsonObjectComplete()]
        if cancel_event is not None:

            class CancelRequested(StoppingCriteria):
                def __call__(self, input_ids: Any, scores: Any, **kwargs: Any) -> bool:
                    return bool(cancel_event.is_set())

            criteria.append(CancelRequested())
        stopping_criteria = StoppingCriteriaList(criteria)
        with torch.inference_mode(), torch.autocast(
            device_type="cuda", dtype=self.autocast_dtype
        ):
            output = self.model.generate(
                **inputs,
                max_new_tokens=self.max_new_tokens,
                do_sample=False,
                use_cache=True,
                pad_token_id=self.tokenizer.eos_token_id,
                stopping_criteria=stopping_criteria,
                repetition_penalty=self.repetition_penalty,
                no_repeat_ngram_size=self.no_repeat_ngram_size,
                logits_processor=logits_processor,
            )
        torch.cuda.synchronize()
        elapsed = time.perf_counter() - start
        generated = output[0][input_length:]
        raw = self.tokenizer.decode(generated, skip_special_tokens=True).strip()
        complete_end = complete_json_object_end(raw)
        if complete_end is not None:
            raw = raw[:complete_end]
        if cancel_event is not None and cancel_event.is_set():
            raise PipelineError("generation cancelled")
        token_count = int(generated.numel())
        return GenerationResult(
            raw_output=raw,
            input_tokens=input_tokens,
            first_token_latency_ms=round(first_ms, 3),
            total_latency_ms=round(elapsed * 1000, 3),
            generated_tokens=token_count,
            tokens_per_second=round(token_count / elapsed, 3) if elapsed else None,
            peak_vram_bytes=int(torch.cuda.max_memory_allocated()),
        )
