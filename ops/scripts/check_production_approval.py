"""Fail-closed validation for a production promotion approval artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ops.scripts.check_exposure_ledger import load_ledger, validate_ledger
from ops.scripts.check_release_manifest import ManifestError, check_readiness, validate_manifest


APPROVAL_SCHEMA = ROOT / "ops" / "approvals" / "production-approval.schema.json"
MANIFEST_SCHEMA = ROOT / "ops" / "manifests" / "release-manifest.schema.json"
BASE_GATE = ROOT / "configs" / "production_gate.json"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_datetime(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("timestamp must be a string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp must include timezone")
    return parsed.astimezone(UTC)


def validate_approval(
    approval: dict[str, Any],
    manifest: dict[str, Any],
    manifest_path: Path,
    *,
    now: datetime | None = None,
    base_gate: dict[str, Any] | None = None,
    exposure_errors: list[str] | None = None,
) -> list[str]:
    errors: list[str] = []
    required = {
        "approval_schema_version",
        "environment",
        "release_id",
        "manifest_sha256",
        "change_ticket",
        "approved_at",
        "expires_at",
        "approvers",
        "checks",
    }
    if set(approval) != required:
        errors.append("approval fields do not exactly match the schema contract")
    if approval.get("approval_schema_version") != 1 or approval.get("environment") != "production":
        errors.append("approval must be schema version 1 for production")
    if approval.get("release_id") != manifest.get("release_id"):
        errors.append("approval release_id does not match manifest")
    if approval.get("manifest_sha256") != sha256_file(manifest_path):
        errors.append("approval manifest_sha256 does not match the manifest file")

    try:
        approved_at = parse_datetime(approval.get("approved_at"))
        expires_at = parse_datetime(approval.get("expires_at"))
        current = (now or datetime.now(UTC)).astimezone(UTC)
        if not approved_at <= current < expires_at:
            errors.append("approval is not currently valid")
        if expires_at <= approved_at:
            errors.append("approval expires_at must follow approved_at")
    except ValueError as exc:
        errors.append(f"approval timestamp is invalid: {exc}")

    approvers = approval.get("approvers")
    if not isinstance(approvers, list) or len(approvers) < 3:
        errors.append("at least three approvers are required")
    else:
        roles = {item.get("role") for item in approvers if isinstance(item, dict)}
        identities = {item.get("identity") for item in approvers if isinstance(item, dict)}
        if len(roles) < 3 or len(identities) < 3:
            errors.append("approvers must contain at least three distinct roles and identities")
        allowed_roles = {"release-owner", "data-owner", "security-owner", "operations-owner"}
        if not roles.issubset(allowed_roles):
            errors.append("approval contains an unsupported approver role")

    checks = approval.get("checks")
    required_checks = {
        "staging_acceptance",
        "restore_rehearsal",
        "rollback_fault_injection",
        "exposure_review",
        "production_backup",
        "route_change_approved",
    }
    if not isinstance(checks, dict) or set(checks) != required_checks:
        errors.append("approval checks do not exactly match the required set")
    elif not all(value is True for value in checks.values()):
        errors.append("every production approval check must be true")

    gate = base_gate if base_gate is not None else json.loads(BASE_GATE.read_text(encoding="utf-8"))
    if gate.get("status") != "approved" or gate.get("mutation_allowed") is not True:
        errors.append("configs/production_gate.json is not approved for mutation")
    if not all(value is True for value in (gate.get("checks") or {}).values()):
        errors.append("configs/production_gate.json still contains an unresolved check")
    for error in exposure_errors or []:
        errors.append(f"exposure gate: {error}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--approval", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        approval = json.loads(args.approval.read_text(encoding="utf-8"))
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        approval_schema = json.loads(APPROVAL_SCHEMA.read_text(encoding="utf-8"))
        manifest_schema = json.loads(MANIFEST_SCHEMA.read_text(encoding="utf-8"))
        if not all(isinstance(value, dict) for value in (approval, manifest, approval_schema, manifest_schema)):
            raise ValueError("approval, manifest, and schemas must be JSON objects")
        validate_manifest(approval, approval_schema)
        validate_manifest(manifest, manifest_schema)
        if manifest.get("environment") != "production":
            raise ManifestError("promotion manifest must target production")

        class ReadinessArgs:
            readiness = True
            expected_environment = "production"
            expected_build_id = None
            expected_git_commit = None
            expected_prompt_sha256 = None
            expected_response_schema_sha256 = None
            expected_expression_map_sha256 = None
            expected_database_revision = None
            expected_embedding_model_revision = None
            expected_llm_base_model = None
            expected_llm_adapter_revision = None
            expected_llm_adapter_sha256 = None
            expected_llm_generation_profile_id = None
            expected_llm_generation_profile_sha256 = None
            expected_llm_locked_eval_suite_id = None
            expected_llm_locked_eval_source_build_id = None
            expected_llm_locked_eval_manifest_sha256 = None
            expected_llm_independent_suite_validation_sha256 = None
            expected_image_digest: list[tuple[str, str]] = []

        errors = check_readiness(manifest, ReadinessArgs())
        errors.extend(
            validate_approval(
                approval,
                manifest,
                args.manifest,
                exposure_errors=validate_ledger(load_ledger(), production_gate=True),
            )
        )
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError, ManifestError) as exc:
        print(f"production_approval_failed: {exc}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
        return 1
    print(f"PASS production approval release_id={manifest['release_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
