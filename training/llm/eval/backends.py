from __future__ import annotations

import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from training.llm.scripts.common import PipelineError, canonical_json, sha256_text
from training.llm.scripts.modeling import load_base_model


@dataclass(frozen=True)
class GenerationResult:
    raw_output: str
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
        return GenerationResult(
            raw_output=content,
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
        self.metadata = {
            "backend": "local-unsloth",
            "model_repo_or_id": config["model"]["repo_id"],
            "model_revision": config["model"]["revision"],
            "tokenizer_revision": config["model"]["tokenizer_revision"],
            "chat_template_sha256": sha256_text(template),
            "adapter_path": str(adapter_path.resolve()) if adapter_path else None,
        }

    def _inputs(self, system_prompt: str, user_content: str) -> dict[str, Any]:
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
        inputs = self.tokenizer(rendered, return_tensors="pt", add_special_tokens=False)
        return {key: value.to("cuda") for key, value in inputs.items()}

    def generate(
        self,
        system_prompt: str,
        user_content: str,
        cancel_event: Any | None = None,
    ) -> GenerationResult:
        torch = self.torch
        inputs = self._inputs(system_prompt, user_content)
        input_length = int(inputs["input_ids"].shape[-1])
        torch.cuda.empty_cache()
        torch.cuda.reset_peak_memory_stats()
        torch.cuda.synchronize()
        first_start = time.perf_counter()
        with torch.inference_mode():
            self.model.generate(
                **inputs,
                max_new_tokens=1,
                do_sample=False,
                use_cache=True,
                pad_token_id=self.tokenizer.eos_token_id,
            )
        torch.cuda.synchronize()
        first_ms = (time.perf_counter() - first_start) * 1000
        torch.cuda.synchronize()
        start = time.perf_counter()
        stopping_criteria = None
        if cancel_event is not None:
            try:
                from transformers import StoppingCriteria, StoppingCriteriaList
            except ImportError as exc:
                raise PipelineError("Transformers stopping criteria are unavailable") from exc

            class CancelRequested(StoppingCriteria):
                def __call__(self, input_ids: Any, scores: Any, **kwargs: Any) -> bool:
                    return bool(cancel_event.is_set())

            stopping_criteria = StoppingCriteriaList([CancelRequested()])
        with torch.inference_mode():
            output = self.model.generate(
                **inputs,
                max_new_tokens=self.max_new_tokens,
                do_sample=False,
                use_cache=True,
                pad_token_id=self.tokenizer.eos_token_id,
                stopping_criteria=stopping_criteria,
            )
        torch.cuda.synchronize()
        elapsed = time.perf_counter() - start
        generated = output[0][input_length:]
        raw = self.tokenizer.decode(generated, skip_special_tokens=True).strip()
        if cancel_event is not None and cancel_event.is_set():
            raise PipelineError("generation cancelled")
        token_count = int(generated.numel())
        return GenerationResult(
            raw_output=raw,
            first_token_latency_ms=round(first_ms, 3),
            total_latency_ms=round(elapsed * 1000, 3),
            generated_tokens=token_count,
            tokens_per_second=round(token_count / elapsed, 3) if elapsed else None,
            peak_vram_bytes=int(torch.cuda.max_memory_allocated()),
        )
