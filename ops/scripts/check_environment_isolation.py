"""Fail-closed static checks for Meguri environment isolation.

The checker reads committed Compose and env examples only. It never contacts a
Docker daemon or a remote host and never reads the secret files referenced by
the env examples.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import yaml


ROOT = Path(__file__).resolve().parents[2]
COMPOSE_ROOT = ROOT / "ops" / "compose"
ENV_ROOT = ROOT / "ops" / "env"
ENVIRONMENTS = ("dev", "staging", "production")
INTERPOLATION = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?:(:-|:\?)([^}]*))?}")


@dataclass(frozen=True)
class Violation:
    code: str
    path: str
    message: str

    def render(self) -> str:
        return f"{self.code} {self.path}: {self.message}"


def read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"{path}:{line_number}: expected KEY=VALUE")
        key, value = line.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError(f"{path}:{line_number}: invalid variable name {key!r}")
        values[key] = value.strip()
    return values


def interpolate_string(value: str, env: dict[str, str]) -> str:
    sentinel = "\u0000MEGURI_DOLLAR\u0000"
    protected = value.replace("$$", sentinel)

    def replace(match: re.Match[str]) -> str:
        name, operator, operand = match.groups()
        current = env.get(name)
        if operator == ":-":
            return current if current else (operand or "")
        if operator == ":?":
            if not current:
                raise ValueError(operand or f"{name} is required")
            return current
        return current or ""

    return INTERPOLATION.sub(replace, protected).replace(sentinel, "$")


def interpolate(value: Any, env: dict[str, str]) -> Any:
    if isinstance(value, str):
        return interpolate_string(value, env)
    if isinstance(value, list):
        return [interpolate(item, env) for item in value]
    if isinstance(value, dict):
        return {key: interpolate(item, env) for key, item in value.items()}
    return value


def deep_merge(base: Any, overlay: Any) -> Any:
    if isinstance(base, dict) and isinstance(overlay, dict):
        merged = copy.deepcopy(base)
        for key, value in overlay.items():
            merged[key] = deep_merge(merged[key], value) if key in merged else copy.deepcopy(value)
        return merged
    return copy.deepcopy(overlay)


def set_path(document: dict[str, Any], path: str, value: Any) -> None:
    parts = path.split(".")
    current: Any = document
    for part in parts[:-1]:
        current = current[int(part)] if isinstance(current, list) else current[part]
    final = parts[-1]
    if isinstance(current, list):
        current[int(final)] = value
    else:
        current[final] = value


def load_fixture(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("fixture must contain a JSON object")
    return value


def load_environment(environment: str, fixture: dict[str, Any] | None = None) -> tuple[dict[str, str], dict[str, Any]]:
    if environment not in ENVIRONMENTS:
        raise ValueError(f"unsupported environment: {environment}")
    fixture = fixture or {}
    env = read_env_file(ENV_ROOT / f"{environment}.env.example")
    if fixture.get("environment") == environment:
        overrides = fixture.get("env_overrides") or {}
        if not isinstance(overrides, dict):
            raise ValueError("env_overrides must be an object")
        env.update({str(key): str(value) for key, value in overrides.items()})

    base = yaml.safe_load((COMPOSE_ROOT / "compose.base.yaml").read_text(encoding="utf-8"))
    overlay = yaml.safe_load((COMPOSE_ROOT / f"compose.{environment}.yaml").read_text(encoding="utf-8"))
    document = deep_merge(base, overlay)
    if fixture.get("environment") == environment:
        patches = fixture.get("compose_overrides") or []
        if not isinstance(patches, list):
            raise ValueError("compose_overrides must be an array")
        for patch in patches:
            set_path(document, str(patch["path"]), patch.get("value"))
    return env, interpolate(document, env)


def walk_strings(value: Any, path: str = "compose") -> Iterable[tuple[str, str]]:
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from walk_strings(item, f"{path}.{index}")
    elif isinstance(value, dict):
        for key, item in value.items():
            yield from walk_strings(item, f"{path}.{key}")


def has_latest_or_implicit_latest(image: str) -> bool:
    reference = image.split("@", 1)[0]
    if "@sha256:" in image:
        return False
    last_component = reference.rsplit("/", 1)[-1]
    if ":" not in last_component:
        return True
    return last_component.rsplit(":", 1)[-1].lower() == "latest"


def volume_target(entry: Any) -> tuple[str | None, str | None]:
    if isinstance(entry, str):
        parts = entry.split(":")
        if len(parts) == 1:
            return None, parts[0]
        return parts[0], parts[1]
    if isinstance(entry, dict):
        return entry.get("source"), entry.get("target")
    return None, None


def validate_environment(environment: str, env: dict[str, str], compose: dict[str, Any]) -> list[Violation]:
    violations: list[Violation] = []
    expected_project = f"meguri-{environment}"
    expected_networks = {
        "edge": f"meguri-{environment}-edge",
        "internal": f"meguri-{environment}-internal",
    }

    def add(code: str, path: str, message: str) -> None:
        violations.append(Violation(code, path, message))

    if compose.get("name") != expected_project:
        add("project_name", "compose.name", f"expected {expected_project!r}")
    if env.get("COMPOSE_PROJECT_NAME") != expected_project:
        add("project_name", "env.COMPOSE_PROJECT_NAME", f"expected {expected_project!r}")
    if env.get("MEGURI_ENV") != environment:
        add("environment_identity", "env.MEGURI_ENV", f"expected {environment!r}")
    if env.get("MEGURI_TENANT_ID") != expected_project:
        add("tenant_identity", "env.MEGURI_TENANT_ID", f"expected {expected_project!r}")

    networks = compose.get("networks") or {}
    for key, expected_name in expected_networks.items():
        actual = (networks.get(key) or {}).get("name")
        if actual != expected_name:
            add("network_identity", f"compose.networks.{key}.name", f"expected {expected_name!r}, got {actual!r}")
    if (networks.get("internal") or {}).get("internal") is not True:
        add("network_internal", "compose.networks.internal.internal", "database network must be internal")

    services = compose.get("services") or {}
    postgres = services.get("postgres") or {}
    migration = services.get("migration") or {}
    core = services.get("core") or {}
    if set(postgres.get("networks") or []) != {"internal"}:
        add("database_network", "compose.services.postgres.networks", "PostgreSQL must join only internal")
    if postgres.get("ports"):
        add("database_public_port", "compose.services.postgres.ports", "PostgreSQL must not publish a host port")
    if set(migration.get("networks") or []) != {"internal"}:
        add("migration_network", "compose.services.migration.networks", "migration must join only internal")
    if migration.get("ports"):
        add("migration_public_port", "compose.services.migration.ports", "migration must not publish a host port")
    if migration.get("restart") not in ("no", False):
        add("migration_restart", "compose.services.migration.restart", "migration must be a one-shot job")
    if migration.get("command") != ["upgrade", "head"]:
        add("migration_command", "compose.services.migration.command", "migration must run 'upgrade head'")
    if set(migration.get("secrets") or []) != {"migration_database_url", "postgres_app_password"}:
        add(
            "migration_secrets",
            "compose.services.migration.secrets",
            "migration must receive only its owner URL and app-role password",
        )
    if set(core.get("networks") or []) != {"edge", "internal"}:
        add("core_network", "compose.services.core.networks", "core must join edge and internal only")
    if {"migration_database_url", "postgres_app_password"}.intersection(core.get("secrets") or []):
        add("core_privilege", "compose.services.core.secrets", "core must not receive migration-owner credentials")
    migration_dependency = (core.get("depends_on") or {}).get("migration") or {}
    if migration_dependency.get("condition") != "service_completed_successfully":
        add(
            "migration_gate",
            "compose.services.core.depends_on.migration.condition",
            "core must wait for a successful migration job",
        )

    postgres_volume_source: str | None = None
    for index, entry in enumerate(postgres.get("volumes") or []):
        source, target = volume_target(entry)
        if target == "/var/lib/postgresql/data":
            postgres_volume_source = source
            if not source:
                add("anonymous_database_volume", f"compose.services.postgres.volumes.{index}", "database volume must be named")
    if postgres_volume_source is None:
        add("database_volume", "compose.services.postgres.volumes", "missing /var/lib/postgresql/data mount")
    expected_volume = f"meguri-{environment}-postgres-data"
    volume_config = (compose.get("volumes") or {}).get(postgres_volume_source or "") or {}
    if volume_config.get("name") != expected_volume:
        add("database_volume", f"compose.volumes.{postgres_volume_source}.name", f"expected {expected_volume!r}")

    for service_name, service in services.items():
        image = str((service or {}).get("image") or "")
        if not image:
            add("image_reference", f"compose.services.{service_name}.image", "image is required")
        elif has_latest_or_implicit_latest(image):
            add("latest_image", f"compose.services.{service_name}.image", f"floating image is forbidden: {image}")

    other_environments = [item for item in ENVIRONMENTS if item != environment]
    for path, value in list(walk_strings(compose)) + [(f"env.{key}", value) for key, value in env.items()]:
        lowered = value.lower().replace("\\", "/")
        for other in other_environments:
            markers = (f"/opt/meguri/{other}/", f"meguri-{other}", f".env.{other}")
            if any(marker in lowered for marker in markers):
                add("cross_environment_reference", path, f"{environment} references {other}: {value}")

    for key in (
        "MEGURI_POSTGRES_PASSWORD_FILE",
        "MEGURI_DATABASE_URL_FILE",
        "MEGURI_MIGRATION_DATABASE_URL_FILE",
        "MEGURI_POSTGRES_APP_PASSWORD_FILE",
        "MEGURI_LLM_API_KEY_FILE",
        "MEGURI_JWT_SECRET_FILE",
        "MEGURI_ASTRBOT_SHARED_TOKEN_FILE",
    ):
        expected_prefix = f"/opt/meguri/{environment}/secrets/"
        if not env.get(key, "").replace("\\", "/").startswith(expected_prefix):
            add("credential_identity", f"env.{key}", f"must use {expected_prefix}")

    for key in ("MEGURI_POSTGRES_DB", "MEGURI_POSTGRES_USER", "MEGURI_POSTGRES_APP_USER"):
        if environment not in env.get(key, "").lower():
            add("credential_identity", f"env.{key}", f"must contain environment marker {environment!r}")
    if env.get("MEGURI_POSTGRES_USER") == env.get("MEGURI_POSTGRES_APP_USER"):
        add("credential_privilege", "env.MEGURI_POSTGRES_APP_USER", "app and migration-owner roles must differ")

    forbidden_plaintext = {
        "POSTGRES_PASSWORD",
        "MEGURI_DATABASE_URL",
        "MEGURI_MIGRATION_DATABASE_URL",
        "MEGURI_POSTGRES_APP_PASSWORD",
        "MEGURI_LLM_API_KEY",
        "MEGURI_JWT_SECRET",
        "MEGURI_ASTRBOT_SHARED_TOKEN",
    }
    for key in forbidden_plaintext.intersection(env):
        add("plaintext_secret", f"env.{key}", "commit only the matching _FILE variable")

    if environment in {"staging", "production"} and env.get("MEGURI_ENABLE_DEBUG_ROUTES", "").lower() != "false":
        add("debug_routes", "env.MEGURI_ENABLE_DEBUG_ROUTES", f"{environment} debug routes must be false")
    try:
        timeout_seconds = float(env.get("MEGURI_LLM_TIMEOUT_SECONDS", ""))
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError
    except ValueError:
        add("llm_runtime", "env.MEGURI_LLM_TIMEOUT_SECONDS", "must be a positive number")
    try:
        if int(env.get("MEGURI_LLM_MAX_CONCURRENCY", "")) <= 0:
            raise ValueError
    except ValueError:
        add("llm_runtime", "env.MEGURI_LLM_MAX_CONCURRENCY", "must be a positive integer")
    expected_channel = {"dev": "mock", "staging": "candidate", "production": "last-good"}[environment]
    if env.get("MEGURI_LLM_RELEASE_CHANNEL") != expected_channel:
        add("llm_release_channel", "env.MEGURI_LLM_RELEASE_CHANNEL", f"expected {expected_channel!r}")
    if environment == "production":
        if env.get("MEGURI_MUTATION_ALLOWED", "").lower() != "false":
            add("production_mutation", "env.MEGURI_MUTATION_ALLOWED", "production mutation must default to false")
        for service_name in ("core", "migration"):
            if (services.get(service_name) or {}).get("build"):
                add(
                    "production_build",
                    f"compose.services.{service_name}.build",
                    "production must deploy prebuilt images",
                )
        if core.get("ports"):
            add("production_public_port", "compose.services.core.ports", "production entry requires a separately approved edge change")

    return violations


def validate_uniqueness(configurations: dict[str, tuple[dict[str, str], dict[str, Any]]]) -> list[Violation]:
    violations: list[Violation] = []
    keys = (
        "MEGURI_POSTGRES_DB",
        "MEGURI_POSTGRES_USER",
        "MEGURI_POSTGRES_APP_USER",
        "MEGURI_TENANT_ID",
        "MEGURI_POSTGRES_VOLUME",
        "MEGURI_EDGE_NETWORK",
        "MEGURI_INTERNAL_NETWORK",
        "MEGURI_DATA_DIR",
        "MEGURI_RELEASE_MANIFEST_FILE",
        "MEGURI_CORE_LOG_DIR",
        "MEGURI_POSTGRES_LOG_DIR",
        "MEGURI_BACKUP_DIR",
        "MEGURI_POSTGRES_PASSWORD_FILE",
        "MEGURI_DATABASE_URL_FILE",
        "MEGURI_MIGRATION_DATABASE_URL_FILE",
        "MEGURI_POSTGRES_APP_PASSWORD_FILE",
        "MEGURI_LLM_API_KEY_FILE",
        "MEGURI_JWT_SECRET_FILE",
        "MEGURI_ASTRBOT_SHARED_TOKEN_FILE",
    )
    for key in keys:
        owners: dict[str, str] = {}
        for environment, (env, _) in configurations.items():
            value = env.get(key, "")
            if value in owners:
                violations.append(
                    Violation(
                        "cross_environment_reuse",
                        f"env.{key}",
                        f"{environment} reuses value from {owners[value]}: {value}",
                    )
                )
            else:
                owners[value] = environment
    return violations


def check_repository(fixture: dict[str, Any] | None = None) -> list[Violation]:
    configurations = {environment: load_environment(environment, fixture) for environment in ENVIRONMENTS}
    violations: list[Violation] = []
    for environment, (env, compose) in configurations.items():
        violations.extend(validate_environment(environment, env, compose))
    violations.extend(validate_uniqueness(configurations))
    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, help="apply a committed negative-test fixture")
    args = parser.parse_args(argv)
    try:
        fixture = load_fixture(args.fixture)
        violations = check_repository(fixture)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, yaml.YAMLError) as exc:
        print(f"checker_error checker: {exc}", file=sys.stderr)
        return 2
    if violations:
        for violation in sorted(violations, key=lambda item: (item.code, item.path, item.message)):
            print(violation.render(), file=sys.stderr)
        return 1
    print("PASS environment isolation configuration is internally consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
