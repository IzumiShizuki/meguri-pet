from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import UTC, datetime
from pathlib import Path

from ops.scripts.check_production_approval import validate_approval


ROOT = Path(__file__).resolve().parents[1]


def production_manifest() -> dict:
    return {
        "manifest_schema_version": 1,
        "release_id": "meguri-production-r001",
        "environment": "production",
        "git_commit": "0123456789abcdef0123456789abcdef01234567",
        "image_digests": {
            "core": "sha256:" + "0123456789abcdef" * 4,
            "migration": "sha256:" + "123456789abcdef0" * 4,
            "postgres": "sha256:" + "23456789abcdef01" * 4,
        },
        "data_build_id": "meguri_v2_02c3db0c507d7c2d",
        "prompt_sha256": "3456789abcdef012" * 4,
        "response_schema_sha256": "456789abcdef0123" * 4,
        "expression_map_sha256": "56789abcdef01234" * 4,
        "database_revision": "20260714_0001",
        "embedding_model_revision": "embedding-r1",
        "llm_base_model": "meguri-base-r1",
        "llm_adapter_revision": "meguri-adapter-r1",
        "llm_adapter_sha256": "6789abcdef012345" * 4,
        "llm_generation_profile_id": "decode-v2",
        "llm_generation_profile_sha256": "789abcdef0123456" * 4,
        "llm_locked_eval_suite_id": "meguri-locked-eval-v2",
        "llm_locked_eval_manifest_sha256": "89abcdef01234567" * 4,
        "model_registry_id": "meguri-production-model-r1",
        "tests": {"python": "passed", "typescript": "passed", "integration": "passed"},
        "generated_at": "2026-07-14T08:00:00Z",
    }


def approval(manifest_path: Path) -> dict:
    return {
        "approval_schema_version": 1,
        "environment": "production",
        "release_id": "meguri-production-r001",
        "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        "change_ticket": "CHG-2026-0001",
        "approved_at": "2026-07-14T09:00:00Z",
        "expires_at": "2026-07-14T15:00:00Z",
        "approvers": [
            {"role": "release-owner", "identity": "release@example.test"},
            {"role": "data-owner", "identity": "data@example.test"},
            {"role": "security-owner", "identity": "security@example.test"},
        ],
        "checks": {
            "staging_acceptance": True,
            "restore_rehearsal": True,
            "rollback_fault_injection": True,
            "exposure_review": True,
            "production_backup": True,
            "route_change_approved": True,
        },
    }


class ProductionApprovalTests(unittest.TestCase):
    def test_complete_approval_can_pass_only_with_resolved_external_gates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.json"
            manifest = production_manifest()
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            errors = validate_approval(
                approval(manifest_path),
                manifest,
                manifest_path,
                now=datetime(2026, 7, 14, 12, tzinfo=UTC),
                base_gate={"status": "approved", "mutation_allowed": True, "checks": {"all": True}},
                exposure_errors=[],
            )
            self.assertEqual(errors, [])

    def test_duplicate_approvers_and_false_check_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.json"
            manifest = production_manifest()
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            value = approval(manifest_path)
            value["approvers"][1] = dict(value["approvers"][0])
            value["checks"]["restore_rehearsal"] = False
            errors = validate_approval(
                value,
                manifest,
                manifest_path,
                now=datetime(2026, 7, 14, 12, tzinfo=UTC),
                base_gate={"status": "approved", "mutation_allowed": True, "checks": {"all": True}},
                exposure_errors=[],
            )
            self.assertTrue(any("distinct roles" in error for error in errors))
            self.assertTrue(any("every production approval check" in error for error in errors))

    def test_current_repository_gates_keep_cli_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.json"
            manifest_path.write_text(json.dumps(production_manifest()), encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "ops" / "scripts" / "check_production_approval.py"),
                    "--approval",
                    str(ROOT / "ops" / "approvals" / "blocked.production-approval.json"),
                    "--manifest",
                    str(manifest_path),
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 1)
            self.assertIn("production_gate.json is not approved", result.stderr)
            self.assertIn("exposure gate", result.stderr)

    def test_workflows_separate_ci_staging_and_production_authority(self) -> None:
        ci = (ROOT / ".github" / "workflows" / "environment-ci.yml").read_text(encoding="utf-8")
        staging = (ROOT / ".github" / "workflows" / "staging-deploy.yml").read_text(encoding="utf-8")
        production = (ROOT / ".github" / "workflows" / "production-approval.yml").read_text(encoding="utf-8")
        self.assertIn("check_environment_isolation.py", ci)
        self.assertIn("--production-gate", ci)
        self.assertIn("workflow_dispatch", staging)
        self.assertIn("environment: staging", staging)
        self.assertIn("DEPLOY_STAGING", staging)
        self.assertIn("check_production_approval.py", production)
        self.assertNotIn("deploy_staging.py", production)
        self.assertNotIn("docker compose up", production)


if __name__ == "__main__":
    unittest.main()
