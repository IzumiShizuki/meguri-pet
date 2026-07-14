"""Validate complete runtime evidence for Meguri staging acceptance."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REQUIRED_CHECKS = {
    "compose_project_identity",
    "network_isolation",
    "data_isolation",
    "account_isolation",
    "named_volume_isolation",
    "empty_database_migration",
    "failed_migration_blocks_core",
    "backup_created",
    "restore_rehearsal",
    "image_fault_rollback",
    "readiness_fault_rollback",
    "protected_services_unchanged",
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def validate_acceptance(value: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if value.get("acceptance_schema_version") != 1 or value.get("environment") != "staging":
        errors.append("acceptance identity must be schema version 1 for staging")
    if value.get("status") != "passed":
        errors.append("staging acceptance status is not passed")
    release_id = value.get("release_id")
    if not isinstance(release_id, str) or not release_id.startswith("meguri-staging-"):
        errors.append("release_id must identify an immutable staging release")
    for field in (
        "release_manifest_sha256",
        "restore_metadata_sha256",
        "server_inventory_before_sha256",
        "server_inventory_after_sha256",
    ):
        if not isinstance(value.get(field), str) or SHA256.fullmatch(value[field]) is None:
            errors.append(f"{field} must contain a SHA-256 evidence digest")
    checks = value.get("checks")
    if not isinstance(checks, dict) or set(checks) != REQUIRED_CHECKS:
        errors.append("acceptance checks do not exactly match the required set")
    elif not all(result is True for result in checks.values()):
        errors.append("every staging acceptance check must be true")
    if not str(value.get("reason", "")).strip():
        errors.append("acceptance reason/evidence summary is required")
    if not str(value.get("generated_at", "")).strip():
        errors.append("generated_at is required")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args(argv)
    try:
        value = json.loads(args.evidence.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("evidence must contain an object")
        errors = validate_acceptance(value)
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        print(f"staging_acceptance_error: {exc}", file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
        return 1
    print(f"PASS staging acceptance release_id={value['release_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
