from __future__ import annotations

import asyncio
import gc
import json
import threading
from pathlib import Path
from typing import Any

from services.meguri_core.schemas import LlmResponse
from training.llm.eval.backends import LocalUnslothBackend
from training.llm.scripts.common import PipelineError, load_yaml, read_json, sha256_text
from training.llm.scripts.export_adapter import adapter_hash


class RegistryModelManager:
    def __init__(self, registry_path: Path, routing_path: Path) -> None:
        self.registry_path = registry_path.resolve()
        self.routing_path = routing_path.resolve()
        self._loaded_model_id: str | None = None
        self._backend: LocalUnslothBackend | None = None
        self._load_lock = asyncio.Lock()

    def _state(self) -> tuple[dict[str, Any], dict[str, Any]]:
        return read_json(self.registry_path), read_json(self.routing_path)

    def active_model_id(self) -> str | None:
        _, routing = self._state()
        if routing.get("candidate_enabled"):
            return routing.get("candidate_model_id")
        return routing.get("last_good_model_id")

    def resolve_requested_model(self, requested: str) -> str:
        _, routing = self._state()
        aliases = {
            "meguri-text-staging-candidate": routing.get("candidate_model_id"),
            "candidate": routing.get("candidate_model_id"),
            "last-good": routing.get("last_good_model_id"),
        }
        resolved = aliases.get(requested, requested)
        active = self.active_model_id()
        if not resolved or resolved != active:
            raise PipelineError("requested model is not the active staging route")
        return str(resolved)

    def readiness(self) -> dict[str, Any]:
        registry, _ = self._state()
        active = self.active_model_id()
        entry = next((item for item in registry.get("models", []) if item.get("model_id") == active), None)
        issues = []
        if not active:
            issues.append("no active model route")
        if entry is None:
            issues.append("active model is not registered")
        elif entry.get("status") not in {"staging_candidate", "staging_active", "production_active"}:
            issues.append("active model status is not staging eligible")
        else:
            try:
                digest, _ = adapter_hash(Path(entry["artifact_path"]))
                if digest != entry["adapter_sha256"]:
                    issues.append("active adapter digest mismatch")
            except PipelineError:
                issues.append("active adapter artifact is unavailable")
        return {
            "ready": not issues,
            "active_model_id": active,
            "loaded_model_id": self._loaded_model_id,
            "issues": issues,
        }

    async def _ensure_loaded(self, model_id: str) -> tuple[LocalUnslothBackend, dict[str, Any]]:
        async with self._load_lock:
            registry, _ = self._state()
            entry = next((item for item in registry["models"] if item["model_id"] == model_id), None)
            if entry is None:
                raise PipelineError("active model registry entry is missing")
            if self._backend is not None and self._loaded_model_id == model_id:
                return self._backend, entry
            if self._backend is not None:
                self._backend = None
                self._loaded_model_id = None
                gc.collect()
                try:
                    import torch

                    if torch.cuda.is_available():
                        torch.cuda.empty_cache()
                except ImportError:
                    pass
            artifact = Path(entry["artifact_path"])
            digest, _ = adapter_hash(artifact)
            if digest != entry["adapter_sha256"] or digest[:16] != entry["adapter_revision"]:
                raise PipelineError("registered adapter digest verification failed")
            config = load_yaml(Path(entry["training_config"]))
            if config["model"]["revision"] != entry["base_revision"]:
                raise PipelineError("registry base revision differs from training config")
            if config["model"]["tokenizer_revision"] != entry["tokenizer_revision"]:
                raise PipelineError("registry tokenizer revision differs from training config")
            backend = await asyncio.to_thread(
                LocalUnslothBackend,
                config,
                allow_download=False,
                adapter_path=artifact,
                max_new_tokens=256,
            )
            self._backend = backend
            self._loaded_model_id = model_id
            return backend, entry

    async def generate(
        self,
        requested_model: str,
        messages: list[dict[str, str]],
        cancel_event: threading.Event,
    ) -> tuple[LlmResponse, dict[str, str]]:
        model_id = self.resolve_requested_model(requested_model)
        backend, entry = await self._ensure_loaded(model_id)
        systems = [item["content"] for item in messages if item["role"] == "system"]
        users = [item["content"] for item in messages if item["role"] == "user"]
        if not systems or not users:
            raise PipelineError("gateway request requires system and user messages")
        if sha256_text(systems[-1].strip()) != entry["prompt_sha256"]:
            raise PipelineError("request system prompt hash differs from the registered model contract")
        result = await asyncio.to_thread(backend.generate, systems[-1], users[-1], cancel_event)
        try:
            validated = LlmResponse.model_validate(json.loads(result.raw_output))
        except Exception as exc:
            raise PipelineError("model output failed the Meguri response schema") from exc
        return validated, {
            "model_id": model_id,
            "base_revision": entry["base_revision"],
            "adapter_revision": entry["adapter_revision"],
            "adapter_sha256": entry["adapter_sha256"],
            "prompt_sha256": entry["prompt_sha256"],
            "response_schema_sha256": entry["response_schema_sha256"],
        }
