from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from ops.scripts.check_agent_environment_contracts import load_contract, validate_contracts
from ops.scripts.check_protected_server_invariants import validate_inventory
from ops.scripts.check_staging_acceptance import REQUIRED_CHECKS, validate_acceptance


ROOT = Path(__file__).resolve().parents[1]
BASELINE = json.loads(
    (ROOT / "ops" / "baselines" / "protected-server-invariants.json").read_text(encoding="utf-8")
)


def protected_inventory() -> dict:
    return {
        "containers": {name: "Up 1 day" for name in BASELINE["required_running_containers"]},
        "networks": list(BASELINE["required_networks"]),
        "volumes": list(BASELINE["required_named_volumes"]),
    }


class AgentEnvironmentContractTests(unittest.TestCase):
    def test_memory_and_llm_contracts_pass(self) -> None:
        errors = validate_contracts(
            load_contract("memory-agent.environment-contract.json"),
            load_contract("llm-agent.environment-contract.json"),
        )
        self.assertEqual(errors, [])

    def test_migration_owner_or_public_llm_regression_fails(self) -> None:
        memory = load_contract("memory-agent.environment-contract.json")
        llm = load_contract("llm-agent.environment-contract.json")
        memory["database"]["migration_url_available_to_core"] = True
        llm["endpoint"]["unauthenticated_public_endpoint_allowed"] = True
        errors = validate_contracts(memory, llm)
        self.assertTrue(any("migration owner" in error for error in errors))
        self.assertTrue(any("unauthenticated public" in error for error in errors))


class ProtectedServerInvariantTests(unittest.TestCase):
    def test_protected_inventory_passes_without_meguri_objects(self) -> None:
        self.assertEqual(validate_inventory(BASELINE, protected_inventory(), expect_no_meguri=True), [])

    def test_missing_stopped_or_unexpected_objects_fail(self) -> None:
        inventory = protected_inventory()
        inventory["containers"].pop("astrbot")
        inventory["containers"]["infra-postgres"] = "Exited (1)"
        inventory["volumes"].append("meguri-staging-postgres-data")
        errors = validate_inventory(BASELINE, inventory, expect_no_meguri=True)
        self.assertTrue(any("astrbot" in error and "missing" in error for error in errors))
        self.assertTrue(any("infra-postgres" in error and "not running" in error for error in errors))
        self.assertTrue(any("unexpected Meguri" in error for error in errors))


class StagingAcceptanceEvidenceTests(unittest.TestCase):
    def test_complete_evidence_can_pass(self) -> None:
        value = {
            "acceptance_schema_version": 1,
            "environment": "staging",
            "status": "passed",
            "release_id": "meguri-staging-r001",
            "release_manifest_sha256": "1" * 64,
            "restore_metadata_sha256": "2" * 64,
            "server_inventory_before_sha256": "3" * 64,
            "server_inventory_after_sha256": "4" * 64,
            "checks": {name: True for name in REQUIRED_CHECKS},
            "reason": "All isolated staging runtime checks passed.",
            "generated_at": "2026-07-14T15:00:00Z",
        }
        self.assertEqual(validate_acceptance(value), [])

    def test_blocked_repository_evidence_remains_failed(self) -> None:
        value = json.loads(
            (ROOT / "ops" / "acceptance" / "blocked.staging-acceptance.json").read_text(encoding="utf-8")
        )
        errors = validate_acceptance(value)
        self.assertTrue(any("status is not passed" in error for error in errors))
        self.assertTrue(any("every staging acceptance check" in error for error in errors))

    def test_missing_fault_injection_result_fails_closed(self) -> None:
        value = {
            "acceptance_schema_version": 1,
            "environment": "staging",
            "status": "passed",
            "release_id": "meguri-staging-r001",
            "release_manifest_sha256": "1" * 64,
            "restore_metadata_sha256": "2" * 64,
            "server_inventory_before_sha256": "3" * 64,
            "server_inventory_after_sha256": "4" * 64,
            "checks": {name: True for name in REQUIRED_CHECKS},
            "reason": "Incomplete fault evidence.",
            "generated_at": "2026-07-14T15:00:00Z",
        }
        value["checks"].pop("image_fault_rollback")
        self.assertTrue(any("exactly match" in error for error in validate_acceptance(value)))


if __name__ == "__main__":
    unittest.main()
