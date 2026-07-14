"""Runtime identity and readiness checks for managed Meguri environments."""

from __future__ import annotations

import hashlib
import json
import os
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path
from typing import Any

from .config import BUILD_ID, RESPONSE_SCHEMA_PATH, SYSTEM_PROMPT_PATH
from .secrets import SecretConfigurationError, read_secret


MANAGED_ENVIRONMENTS = {"dev", "staging", "production"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_adapter_revision(value: Any) -> str | None:
    return None if value in {None, "", "none", "null"} else str(value)


def provider_name(provider: object) -> str:
    explicit = getattr(provider, "provider_name", None)
    if explicit:
        return str(explicit)
    name = type(provider).__name__.lower()
    return "fake" if name == "fakememoryprovider" else name


async def probe_database(database_url: str) -> str:
    try:
        import asyncpg
    except ImportError as exc:  # pragma: no cover - packaging failure path
        raise RuntimeError("asyncpg is unavailable") from exc

    if database_url.startswith("postgresql+asyncpg://"):
        database_url = database_url.replace("postgresql+asyncpg://", "postgresql://", 1)
    if not database_url.startswith("postgresql://"):
        raise RuntimeError("database URL must use PostgreSQL")
    connection = await asyncpg.connect(database_url, timeout=3)
    try:
        revision = await connection.fetchval("SELECT version_num FROM alembic_version")
    finally:
        await connection.close()
    if not revision:
        raise RuntimeError("alembic_version is empty")
    return str(revision)


class ReadinessEvaluator:
    def __init__(
        self,
        orchestrator: object,
        *,
        env: Mapping[str, str] | None = None,
        build_id: str = BUILD_ID,
        prompt_path: Path = SYSTEM_PROMPT_PATH,
        response_schema_path: Path = RESPONSE_SCHEMA_PATH,
        database_probe: Callable[[str], Awaitable[str]] = probe_database,
    ) -> None:
        self.orchestrator = orchestrator
        self.env = os.environ if env is None else env
        self.build_id = build_id
        self.prompt_path = prompt_path
        self.response_schema_path = response_schema_path
        self.database_probe = database_probe

    async def evaluate(self) -> dict[str, Any]:
        environment = self.env.get("MEGURI_ENV", "local").strip().lower() or "local"
        release_id = self.env.get("MEGURI_RELEASE_ID", "local-unmanaged").strip() or "local-unmanaged"
        if environment not in MANAGED_ENVIRONMENTS:
            return {
                "status": "ready",
                "service": "meguri-core",
                "environment": environment,
                "release_id": release_id,
                "build_id": self.build_id,
                "checks": {"local_unmanaged": "passed"},
            }

        checks: dict[str, str] = {}
        failures: list[str] = []

        def check(name: str, operation: Callable[[], None]) -> None:
            try:
                operation()
            except (OSError, ValueError, KeyError, TypeError, SecretConfigurationError, RuntimeError) as exc:
                checks[name] = "failed"
                failures.append(f"{name}: {exc}")
            else:
                checks[name] = "passed"

        manifest: dict[str, Any] = {}

        def load_manifest() -> None:
            nonlocal manifest
            path = self.env.get("MEGURI_RELEASE_MANIFEST_PATH", "").strip()
            if not path:
                raise RuntimeError("MEGURI_RELEASE_MANIFEST_PATH is required")
            decoded = json.loads(Path(path).read_text(encoding="utf-8"))
            if not isinstance(decoded, dict):
                raise RuntimeError("release manifest must be a JSON object")
            manifest = decoded

        check("release_manifest", load_manifest)

        def identity() -> None:
            expected = {
                "environment": environment,
                "release_id": release_id,
                "data_build_id": self.env.get("MEGURI_DATA_BUILD_ID"),
                "database_revision": self.env.get("MEGURI_DATABASE_REVISION"),
                "embedding_model_revision": self.env.get("MEGURI_EMBEDDING_MODEL_REVISION"),
                "llm_base_model": self.env.get("MEGURI_LLM_BASE_MODEL_REVISION"),
            }
            for field, value in expected.items():
                if not value:
                    raise RuntimeError(f"runtime {field} is missing")
                if manifest.get(field) != value:
                    raise RuntimeError(f"{field} does not match the release manifest")
            actual_adapter = normalize_adapter_revision(self.env.get("MEGURI_LLM_ADAPTER_REVISION"))
            if normalize_adapter_revision(manifest.get("llm_adapter_revision")) != actual_adapter:
                raise RuntimeError("llm_adapter_revision does not match the release manifest")
            actual_adapter_sha256 = normalize_adapter_revision(self.env.get("MEGURI_LLM_ADAPTER_SHA256"))
            if normalize_adapter_revision(manifest.get("llm_adapter_sha256")) != actual_adapter_sha256:
                raise RuntimeError("llm_adapter_sha256 does not match the release manifest")
            if self.build_id != expected["data_build_id"]:
                raise RuntimeError("mounted data build does not match MEGURI_DATA_BUILD_ID")

        check("release_identity", identity)

        def artifacts() -> None:
            expression_path = self.env.get("MEGURI_EXPRESSION_MAP_PATH", "").strip()
            if not expression_path:
                raise RuntimeError("MEGURI_EXPRESSION_MAP_PATH is required")
            expected = {
                "prompt_sha256": sha256_file(self.prompt_path),
                "response_schema_sha256": sha256_file(self.response_schema_path),
                "expression_map_sha256": sha256_file(Path(expression_path)),
            }
            mismatches = [field for field, digest in expected.items() if manifest.get(field) != digest]
            if mismatches:
                raise RuntimeError(f"artifact digest mismatch: {', '.join(mismatches)}")

        check("artifact_digests", artifacts)

        def secrets() -> None:
            read_secret(self.env, "MEGURI_DATABASE_URL")
            read_secret(self.env, "MEGURI_JWT_SECRET")
            read_secret(self.env, "MEGURI_ASTRBOT_SHARED_TOKEN")
            llm_required = self.env.get("MEGURI_LLM_PROVIDER", "mock").strip().lower() != "mock"
            read_secret(self.env, "MEGURI_LLM_API_KEY", required=llm_required)

        check("secret_files", secrets)

        def providers() -> None:
            actual_memory = provider_name(getattr(self.orchestrator, "memory"))
            actual_llm = provider_name(getattr(self.orchestrator, "llm"))
            expected_memory = self.env.get("MEGURI_MEMORY_PROVIDER", "").strip().lower()
            expected_llm = self.env.get("MEGURI_LLM_PROVIDER", "").strip().lower()
            if actual_memory != expected_memory:
                raise RuntimeError("Memory provider does not match runtime configuration")
            if actual_llm != expected_llm:
                raise RuntimeError("LLM provider does not match runtime configuration")

        check("providers", providers)

        try:
            database_url = read_secret(self.env, "MEGURI_DATABASE_URL")
            actual_revision = await self.database_probe(str(database_url))
            expected_revision = self.env.get("MEGURI_DATABASE_REVISION", "")
            if actual_revision != expected_revision:
                raise RuntimeError("database revision does not match runtime configuration")
        except (OSError, ValueError, TypeError, SecretConfigurationError, RuntimeError) as exc:
            checks["database_revision"] = "failed"
            failures.append(f"database_revision: {exc}")
        else:
            checks["database_revision"] = "passed"

        return {
            "status": "ready" if not failures else "not_ready",
            "service": "meguri-core",
            "environment": environment,
            "release_id": release_id,
            "build_id": self.build_id,
            "checks": checks,
            "failures": failures,
        }
