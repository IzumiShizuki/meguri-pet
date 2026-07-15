from __future__ import annotations

import asyncio
import gc
import json
import threading
from pathlib import Path
from typing import Any

from services.meguri_core.schemas import LlmResponse
from training.llm.eval.backends import LocalUnslothBackend
from training.llm.generation_profile import GenerationProfile, load_generation_profile
from training.llm.scripts.common import (
    PipelineError,
    canonical_json,
    load_yaml,
    read_json,
    sha256_file,
    sha256_text,
)
from training.llm.scripts.export_adapter import adapter_hash


class RegistryModelManager:
    def __init__(self, registry_path: Path, routing_path: Path) -> None:
        self.registry_path = registry_path.resolve()
        self.routing_path = routing_path.resolve()
        self._loaded_model_id: str | None = None
        self._loaded_runtime_identity: tuple[str, str, str | None] | None = None
        self._backend: LocalUnslothBackend | None = None
        self._load_lock = asyncio.Lock()

    def _state(self) -> tuple[dict[str, Any], dict[str, Any]]:
        return read_json(self.registry_path), read_json(self.routing_path)

    def active_model_id(self) -> str | None:
        _, routing = self._state()
        if routing.get("candidate_enabled"):
            return routing.get("candidate_model_id")
        return routing.get("last_good_model_id")

    def _runtime_spec(
        self,
        entry: dict[str, Any],
        artifact: Path,
    ) -> tuple[dict[str, Any], dict[str, Any], GenerationProfile | None]:
        config = load_yaml(Path(entry["training_config"]))
        if config["model"]["repo_id"] != entry["base_model"]:
            raise PipelineError("registry base model differs from training config")
        if config["model"]["revision"] != entry["base_revision"]:
            raise PipelineError("registry base revision differs from training config")
        if config["model"]["tokenizer_revision"] != entry["tokenizer_revision"]:
            raise PipelineError("registry tokenizer revision differs from training config")
        profile_fields = (
            entry.get("generation_profile"),
            entry.get("generation_profile_id"),
            entry.get("generation_profile_sha256"),
        )
        if all(value is None for value in profile_fields):
            return config, {"max_new_tokens": 256}, None
        if any(value is None for value in profile_fields):
            raise PipelineError("registry generation profile identity is incomplete")
        if (
            not entry.get("locked_eval_suite_id")
            or not entry.get("locked_eval_source_build_id")
            or not entry.get("locked_eval_manifest_sha256")
            or not entry.get("independent_suite_validation_sha256")
        ):
            raise PipelineError("registry locked-eval suite identity is incomplete")
        profile = load_generation_profile(
            Path(str(profile_fields[0])),
            training_config=config,
            adapter_path=artifact,
        )
        if profile.profile_id != profile_fields[1]:
            raise PipelineError("registered generation profile ID mismatch")
        if profile.sha256 != profile_fields[2]:
            raise PipelineError("registered generation profile digest mismatch")
        locked_path = Path(entry["locked_eval_report"])
        comparison_path = Path(entry["comparison_report"])
        locked = read_json(locked_path)
        comparison = read_json(comparison_path)
        locked_provenance = locked.get("provenance", {})
        comparison_provenance = comparison.get("provenance", {})
        if locked.get("status") != "pass" or locked.get("counts", {}).get("total") != 184:
            raise PipelineError("registered locked evaluation is incomplete")
        if comparison.get("candidate", {}).get("run_id") != locked.get("run_id"):
            raise PipelineError("registered comparison candidate differs from locked eval")
        if comparison.get("staging_gate", {}).get("status") != "pass":
            raise PipelineError("registered comparison does not pass the staging gate")
        expected_identity = (
            entry["generation_profile_id"],
            entry["generation_profile_sha256"],
            entry["locked_eval_suite_id"],
            entry["locked_eval_source_build_id"],
            entry["locked_eval_manifest_sha256"],
        )
        for label, provenance in (
            ("locked eval", locked_provenance),
            ("comparison", comparison_provenance),
        ):
            actual_identity = (
                provenance.get("generation_profile_id"),
                provenance.get("generation_profile_sha256"),
                provenance.get("locked_eval_suite_id"),
                provenance.get("locked_eval_source_build_id"),
                provenance.get("locked_eval_manifest_sha256"),
            )
            if actual_identity != expected_identity:
                raise PipelineError(f"registered {label} runtime identity mismatch")
        if comparison_provenance.get("candidate_report") != sha256_file(locked_path):
            raise PipelineError("registered comparison does not bind the locked-eval report")
        independent_validation = locked.get("independent_suite_validation")
        if not isinstance(independent_validation, dict) or independent_validation.get("status") != "pass":
            raise PipelineError("registered locked suite independence validation did not pass")
        independent_validation_sha256 = sha256_text(canonical_json(independent_validation))
        if independent_validation_sha256 != entry["independent_suite_validation_sha256"]:
            raise PipelineError("registered locked suite independence digest mismatch")
        if (
            comparison_provenance.get("independent_suite_validation_sha256")
            != independent_validation_sha256
        ):
            raise PipelineError("registered comparison suite independence digest mismatch")
        return config, profile.backend_kwargs(), profile

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
                self._runtime_spec(entry, Path(entry["artifact_path"]))
            except (KeyError, PipelineError):
                issues.append("active model runtime identity is invalid or unavailable")
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
            if entry.get("status") not in {
                "staging_candidate",
                "staging_active",
                "production_active",
            }:
                raise PipelineError("active model status is not inference eligible")
            runtime_identity = (
                model_id,
                str(entry.get("adapter_sha256")),
                entry.get("generation_profile_sha256"),
            )
            if self._backend is not None and self._loaded_runtime_identity == runtime_identity:
                return self._backend, entry
            if self._backend is not None:
                self._backend = None
                self._loaded_model_id = None
                self._loaded_runtime_identity = None
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
            config, generation, _ = self._runtime_spec(entry, artifact)
            backend = await asyncio.to_thread(
                LocalUnslothBackend,
                config,
                allow_download=False,
                adapter_path=artifact,
                **generation,
            )
            self._backend = backend
            self._loaded_model_id = model_id
            self._loaded_runtime_identity = runtime_identity
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
        metadata = {
            "model_id": model_id,
            "base_revision": entry["base_revision"],
            "adapter_revision": entry["adapter_revision"],
            "adapter_sha256": entry["adapter_sha256"],
            "prompt_sha256": entry["prompt_sha256"],
            "response_schema_sha256": entry["response_schema_sha256"],
        }
        if entry.get("generation_profile_id") is not None:
            metadata["generation_profile_id"] = entry["generation_profile_id"]
            metadata["generation_profile_sha256"] = entry["generation_profile_sha256"]
        return validated, metadata
