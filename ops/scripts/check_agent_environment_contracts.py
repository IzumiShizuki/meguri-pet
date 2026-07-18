"""Validate Memory and LLM Agent environment handoff contracts."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = ROOT / "ops" / "contracts"


def load_contract(name: str) -> dict[str, Any]:
    value = json.loads((CONTRACT_ROOT / name).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{name} must contain an object")
    return value


def validate_contracts(memory: dict[str, Any], llm: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    for name, contract in (("memory", memory), ("llm", llm)):
        if contract.get("contract_version") != 1 or contract.get("agent") != name:
            errors.append(f"{name}: invalid contract identity")
        if not contract.get("status") or not contract.get("authority"):
            errors.append(f"{name}: status and authority are required")
        if not contract.get("handoff_gates"):
            errors.append(f"{name}: handoff gates must be explicit")
        if not contract.get("forbidden_dependencies"):
            errors.append(f"{name}: forbidden dependencies must be explicit")
        required_manifest_fields = set(contract.get("required_manifest_fields") or [])
        if not required_manifest_fields:
            errors.append(f"{name}: required Manifest fields are missing")

    environments = memory.get("environments") or {}
    if set(environments) != {"dev", "staging", "production"}:
        errors.append("memory: all three environments are required")
    for environment, values in environments.items():
        marker = f"meguri-{environment}"
        if values.get("project") != marker or marker not in values.get("internal_network", ""):
            errors.append(f"memory: {environment} project/network identity mismatch")
        if environment not in values.get("database", ""):
            errors.append(f"memory: {environment} database identity mismatch")
    database = memory.get("database") or {}
    if database.get("service_dns") != "postgres:5432":
        errors.append("memory: database must use isolated Compose DNS")
    if database.get("app_url_file") != "/run/secrets/database_url":
        errors.append("memory: app URL must use the core file-secret mount")
    if database.get("migration_url_available_to_core") is not False:
        errors.append("memory: migration owner must not be available to core")
    forbidden_memory = set(memory.get("forbidden_dependencies") or [])
    if not {"infra-postgres", "infra-postgres-data", "shizuki-memoryos"}.issubset(forbidden_memory):
        errors.append("memory: protected existing dependencies are not forbidden")

    endpoint = llm.get("endpoint") or {}
    if endpoint.get("api_key_file") != "/run/secrets/llm_api_key":
        errors.append("llm: API key must use the file-secret mount")
    if endpoint.get("model_registry_variable") != "MEGURI_MODEL_REGISTRY_ID":
        errors.append("llm: runtime model registry identity variable is missing")
    if endpoint.get("non_loopback_tls_required") is not True:
        errors.append("llm: non-loopback TLS must be required")
    if endpoint.get("unauthenticated_public_endpoint_allowed") is not False:
        errors.append("llm: unauthenticated public inference must be forbidden")
    if endpoint.get("cloud_model_weight_mount_allowed") is not False:
        errors.append("llm: private model weights must not be mounted on the cloud host")
    if endpoint.get("generation_profile_id_variable") != "MEGURI_LLM_GENERATION_PROFILE_ID":
        errors.append("llm: generation profile ID variable is invalid")
    if endpoint.get("generation_profile_sha256_variable") != "MEGURI_LLM_GENERATION_PROFILE_SHA256":
        errors.append("llm: generation profile digest variable is invalid")
    if llm.get("release_channels") != {"dev": "mock", "staging": "candidate", "production": "last-good"}:
        errors.append("llm: release channels are invalid")
    required_llm = {
        "llm_base_model",
        "llm_adapter_revision",
        "llm_adapter_sha256",
        "llm_generation_profile_id",
        "llm_generation_profile_sha256",
        "llm_locked_eval_suite_id",
        "llm_locked_eval_source_build_id",
        "llm_locked_eval_manifest_sha256",
        "llm_independent_suite_validation_sha256",
        "model_registry_id",
        "prompt_sha256",
        "response_schema_sha256",
    }
    if not required_llm.issubset(llm.get("required_manifest_fields") or []):
        errors.append("llm: model registration Manifest fields are incomplete")
    return errors


def main() -> int:
    try:
        errors = validate_contracts(
            load_contract("memory-agent.environment-contract.json"),
            load_contract("llm-agent.environment-contract.json"),
        )
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        print(f"agent_contract_error: {exc}", file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
        return 1
    print("PASS Memory and LLM Agent environment contracts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
