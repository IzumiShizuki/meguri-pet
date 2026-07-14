from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from ops.deployment.release import (
    DeploymentController,
    DeploymentError,
    preflight_release,
    read_json,
)


DIGESTS = {
    "core": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "migration": "sha256:123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
    "postgres": "sha256:23456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef01",
}


def manifest(release_id: str, database_revision: str = "20260714_0001") -> dict:
    return {
        "manifest_schema_version": 1,
        "release_id": release_id,
        "environment": "staging",
        "git_commit": "0123456789abcdef0123456789abcdef01234567",
        "image_digests": DIGESTS,
        "data_build_id": "meguri_v2_02c3db0c507d7c2d",
        "prompt_sha256": "0123456789abcdef" * 4,
        "response_schema_sha256": "123456789abcdef0" * 4,
        "expression_map_sha256": "23456789abcdef01" * 4,
        "database_revision": database_revision,
        "embedding_model_revision": "embedding-model-r1",
        "llm_base_model": "meguri-base-r1",
        "llm_adapter_revision": "meguri-adapter-r1",
        "model_registry_id": "meguri-text-staging-r1",
        "tests": {"python": "passed", "typescript": "passed", "integration": "passed"},
        "generated_at": "2026-07-14T12:00:00Z",
    }


def write_release(root: Path, release_id: str, database_revision: str = "20260714_0001") -> dict:
    release_root = root / release_id
    release_root.mkdir()
    manifest_path = (release_root / "release-manifest.json").resolve()
    manifest_path.write_text(json.dumps(manifest(release_id, database_revision)), encoding="utf-8")
    env_path = (release_root / "runtime.env").resolve()
    lines = {
        "COMPOSE_PROJECT_NAME": "meguri-staging",
        "MEGURI_ENV": "staging",
        "MEGURI_RELEASE_ID": release_id,
        "MEGURI_RELEASE_MANIFEST_FILE": str(manifest_path),
        "MEGURI_MUTATION_ALLOWED": "false",
        "MEGURI_CORE_PORT": "18080",
        "MEGURI_DATABASE_REVISION": database_revision,
        "MEGURI_CORE_IMAGE": f"registry.example/meguri-core@{DIGESTS['core']}",
        "MEGURI_MIGRATION_IMAGE": f"registry.example/meguri-migration@{DIGESTS['migration']}",
        "MEGURI_POSTGRES_IMAGE": f"pgvector/pgvector@{DIGESTS['postgres']}",
    }
    env_path.write_text("".join(f"{key}={value}\n" for key, value in lines.items()), encoding="utf-8")
    return preflight_release(env_path, manifest_path)


class StagingDeploymentTests(unittest.TestCase):
    def test_preflight_requires_digest_pinned_images(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = write_release(root, "meguri-staging-r001")
            self.assertEqual(state["database_revision"], "20260714_0001")
            env_path = Path(state["env_file"])
            content = env_path.read_text(encoding="utf-8").replace(
                f"registry.example/meguri-core@{DIGESTS['core']}",
                "registry.example/meguri-core:staging",
            )
            env_path.write_text(content, encoding="utf-8")
            with self.assertRaisesRegex(DeploymentError, "immutable sha256 digest"):
                preflight_release(env_path, Path(state["manifest_file"]))

    def test_success_records_last_good_and_command_order(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = write_release(root, "meguri-staging-r001")
            commands: list[list[str]] = []
            probes: list[tuple[str, str]] = []
            controller = DeploymentController(
                root / "state",
                runner=lambda command: commands.append(command),
                probe=lambda url, release_id, _timeout: probes.append((url, release_id)),
            )
            controller.deploy(candidate)

            self.assertEqual(read_json(controller.last_good_path)["release_id"], "meguri-staging-r001")
            self.assertTrue(any(command[-3:] == ["migration", "upgrade", "head"] for command in commands))
            self.assertEqual(probes, [("http://127.0.0.1:18080/health/ready", "meguri-staging-r001")])

    def test_readiness_failure_restores_previous_same_revision(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous = write_release(root, "meguri-staging-r001")
            candidate = write_release(root, "meguri-staging-r002")
            commands: list[list[str]] = []
            probe_releases: list[str] = []

            def probe(_url: str, release_id: str, _timeout: float) -> None:
                probe_releases.append(release_id)
                if release_id.endswith("r002"):
                    raise DeploymentError("injected readiness failure")

            controller = DeploymentController(root / "state", runner=commands.append, probe=probe)
            controller.deploy(previous)
            with self.assertRaisesRegex(DeploymentError, "previous core was left unchanged or restored"):
                controller.deploy(candidate)

            self.assertEqual(read_json(controller.last_good_path)["release_id"], "meguri-staging-r001")
            self.assertEqual(probe_releases[-2:], ["meguri-staging-r002", "meguri-staging-r001"])

    def test_cross_revision_deploy_is_rejected_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous = write_release(root, "meguri-staging-r001")
            candidate = write_release(root, "meguri-staging-r002", "20260714_0002")
            commands: list[list[str]] = []
            controller = DeploymentController(root / "state", runner=commands.append, probe=lambda *_: None)
            controller.deploy(previous)
            command_count = len(commands)
            with self.assertRaisesRegex(DeploymentError, "backup/restore workflow"):
                controller.deploy(candidate)
            self.assertEqual(len(commands), command_count)

    def test_explicit_rollback_swaps_last_good_and_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = write_release(root, "meguri-staging-r001")
            second = write_release(root, "meguri-staging-r002")
            controller = DeploymentController(root / "state", runner=lambda _command: None, probe=lambda *_: None)
            controller.deploy(first)
            controller.deploy(second)
            controller.rollback()
            self.assertEqual(read_json(controller.last_good_path)["release_id"], "meguri-staging-r001")
            self.assertEqual(read_json(controller.rollback_target_path)["release_id"], "meguri-staging-r002")


if __name__ == "__main__":
    unittest.main()
