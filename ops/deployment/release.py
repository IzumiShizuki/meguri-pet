"""Fail-closed staging deployment with last-good rollback state."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from ops.scripts.check_environment_isolation import read_env_file
from ops.scripts.check_release_manifest import ManifestError, placeholder, validate_manifest


ROOT = Path(__file__).resolve().parents[2]
BASE_COMPOSE = ROOT / "ops" / "compose" / "compose.base.yaml"
STAGING_COMPOSE = ROOT / "ops" / "compose" / "compose.staging.yaml"
MANIFEST_SCHEMA = ROOT / "ops" / "manifests" / "release-manifest.schema.json"


class DeploymentError(RuntimeError):
    """A preflight, deployment, health, or rollback step failed."""


def now_iso() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def read_json(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    if not isinstance(value, dict):
        raise DeploymentError(f"state file is not a JSON object: {path.name}")
    return value


def digest_from_image(reference: str) -> str | None:
    if "@sha256:" not in reference:
        return None
    return "sha256:" + reference.rsplit("@sha256:", 1)[1]


def health_url(env: dict[str, str]) -> str:
    port = env.get("MEGURI_CORE_PORT", "18080")
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise DeploymentError("MEGURI_CORE_PORT must be a valid port")
    return f"http://127.0.0.1:{port}/health/ready"


def preflight_release(env_file: Path, manifest_file: Path) -> dict[str, Any]:
    if not env_file.is_absolute() or not manifest_file.is_absolute():
        raise DeploymentError("release env and manifest paths must be absolute")
    env = read_env_file(env_file)
    try:
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        schema = json.loads(MANIFEST_SCHEMA.read_text(encoding="utf-8"))
        if not isinstance(manifest, dict) or not isinstance(schema, dict):
            raise ManifestError("manifest and schema must be objects")
        validate_manifest(manifest, schema)
    except (OSError, json.JSONDecodeError, ManifestError) as exc:
        raise DeploymentError(f"release manifest is invalid: {exc}") from exc

    if env.get("MEGURI_ENV") != "staging" or manifest.get("environment") != "staging":
        raise DeploymentError("this workflow accepts staging releases only")
    if env.get("COMPOSE_PROJECT_NAME") != "meguri-staging":
        raise DeploymentError("COMPOSE_PROJECT_NAME must be meguri-staging")
    if env.get("MEGURI_RELEASE_ID") != manifest.get("release_id"):
        raise DeploymentError("release ID differs between env and manifest")
    if Path(env.get("MEGURI_RELEASE_MANIFEST_FILE", "")) != manifest_file:
        raise DeploymentError("MEGURI_RELEASE_MANIFEST_FILE must point to the candidate manifest")
    if env.get("MEGURI_MUTATION_ALLOWED", "").lower() != "false":
        raise DeploymentError("staging application mutation must default to false")

    critical = (
        "git_commit",
        "data_build_id",
        "prompt_sha256",
        "response_schema_sha256",
        "expression_map_sha256",
        "database_revision",
        "embedding_model_revision",
        "llm_base_model",
    )
    for field in critical:
        if placeholder(manifest.get(field)):
            raise DeploymentError(f"manifest field is not readiness-safe: {field}")
    for name, status in (manifest.get("tests") or {}).items():
        if status != "passed":
            raise DeploymentError(f"manifest test is not passed: {name}")

    image_variables = {
        "core": "MEGURI_CORE_IMAGE",
        "migration": "MEGURI_MIGRATION_IMAGE",
        "postgres": "MEGURI_POSTGRES_IMAGE",
    }
    manifest_digests = manifest.get("image_digests") or {}
    for name, variable in image_variables.items():
        digest = digest_from_image(env.get(variable, ""))
        if digest is None:
            raise DeploymentError(f"{variable} must use an immutable sha256 digest")
        if manifest_digests.get(name) != digest:
            raise DeploymentError(f"{variable} digest differs from manifest image_digests.{name}")

    if env.get("MEGURI_DATABASE_REVISION") != manifest.get("database_revision"):
        raise DeploymentError("database revision differs between env and manifest")
    return {
        "environment": "staging",
        "project_name": "meguri-staging",
        "release_id": str(manifest["release_id"]),
        "env_file": str(env_file),
        "manifest_file": str(manifest_file),
        "health_url": health_url(env),
        "database_revision": str(manifest["database_revision"]),
        "image_digests": {name: manifest_digests[name] for name in image_variables},
        "validated_at": now_iso(),
    }


def compose_command(state: dict[str, Any], docker: str = "docker") -> list[str]:
    return [
        docker,
        "compose",
        "--project-name",
        "meguri-staging",
        "--env-file",
        str(state["env_file"]),
        "-f",
        str(BASE_COMPOSE),
        "-f",
        str(STAGING_COMPOSE),
    ]


def default_runner(command: list[str]) -> None:
    completed = subprocess.run(command, cwd=ROOT, check=False)
    if completed.returncode != 0:
        raise DeploymentError(f"command failed with exit code {completed.returncode}: {' '.join(command[:4])}")


def ready_probe(url: str, release_id: str, timeout_seconds: float) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_error = "no response"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                value = json.loads(response.read().decode("utf-8"))
            if value.get("status") == "ready" and value.get("release_id") == release_id:
                return
            last_error = "readiness payload did not match release"
        except (OSError, urllib.error.URLError, json.JSONDecodeError, AttributeError) as exc:
            last_error = type(exc).__name__
        time.sleep(2)
    raise DeploymentError(f"readiness did not pass before timeout ({last_error})")


class DeploymentController:
    def __init__(
        self,
        state_dir: Path,
        *,
        docker: str = "docker",
        runner: Callable[[list[str]], None] = default_runner,
        probe: Callable[[str, str, float], None] = ready_probe,
        health_timeout: float = 180,
    ) -> None:
        self.state_dir = state_dir
        self.docker = docker
        self.runner = runner
        self.probe = probe
        self.health_timeout = health_timeout

    @property
    def last_good_path(self) -> Path:
        return self.state_dir / "last-good.json"

    @property
    def rollback_target_path(self) -> Path:
        return self.state_dir / "rollback-target.json"

    @property
    def current_path(self) -> Path:
        return self.state_dir / "current.json"

    def validate_compose(self, state: dict[str, Any]) -> None:
        self.runner(compose_command(state, self.docker) + ["config", "--quiet"])

    def activate(self, state: dict[str, Any], *, migrate: bool) -> None:
        command = compose_command(state, self.docker)
        self.validate_compose(state)
        self.runner(command + ["pull", "postgres", "migration", "core"])
        self.runner(command + ["up", "-d", "--wait", "postgres"])
        if migrate:
            self.runner(command + ["run", "--rm", "migration", "upgrade", "head"])
        self.runner(command + ["up", "-d", "--no-deps", "core"])
        self.probe(str(state["health_url"]), str(state["release_id"]), self.health_timeout)

    def deploy(self, candidate: dict[str, Any]) -> None:
        previous = read_json(self.last_good_path)
        if previous and previous.get("database_revision") != candidate.get("database_revision"):
            raise DeploymentError(
                "cross-revision deployment requires the backup/restore workflow; last-good rollback would be unsafe"
            )
        try:
            self.activate(candidate, migrate=True)
        except Exception as exc:
            if previous is not None:
                try:
                    self.activate(previous, migrate=False)
                except Exception as rollback_exc:
                    raise DeploymentError(f"candidate failed and automatic rollback failed: {rollback_exc}") from exc
            raise DeploymentError(f"candidate failed; previous core was left unchanged or restored: {exc}") from exc

        completed = {**candidate, "deployed_at": now_iso()}
        if previous is not None:
            atomic_write_json(self.rollback_target_path, previous)
        atomic_write_json(self.current_path, completed)
        atomic_write_json(self.last_good_path, completed)

    def rollback(self) -> None:
        target = read_json(self.rollback_target_path)
        current = read_json(self.last_good_path)
        if target is None or current is None:
            raise DeploymentError("both rollback-target.json and last-good.json are required")
        if target.get("database_revision") != current.get("database_revision"):
            raise DeploymentError("cross-revision rollback requires a verified database restore")
        self.activate(target, migrate=False)
        restored = {**target, "deployed_at": now_iso(), "rollback_from": current.get("release_id")}
        atomic_write_json(self.current_path, restored)
        atomic_write_json(self.last_good_path, restored)
        atomic_write_json(self.rollback_target_path, current)
