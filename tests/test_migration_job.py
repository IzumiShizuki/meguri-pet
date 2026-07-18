from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from ops.migration import entrypoint
from ops.scripts.check_environment_isolation import ENVIRONMENTS, load_environment


ROOT = Path(__file__).resolve().parents[1]


class MigrationComposeContractTests(unittest.TestCase):
    def test_each_environment_gates_core_on_one_shot_migration(self) -> None:
        for environment in ENVIRONMENTS:
            with self.subTest(environment=environment):
                _, compose = load_environment(environment)
                migration = compose["services"]["migration"]
                core = compose["services"]["core"]

                self.assertEqual(migration["networks"], ["internal"])
                self.assertNotIn("ports", migration)
                self.assertEqual(migration["restart"], "no")
                self.assertEqual(migration["command"], ["upgrade", "head"])
                self.assertEqual(
                    set(migration["secrets"]),
                    {"migration_database_url", "postgres_app_password"},
                )
                self.assertTrue(
                    {"migration_database_url", "postgres_app_password"}.isdisjoint(core["secrets"])
                )
                self.assertEqual(
                    core["depends_on"]["migration"]["condition"],
                    "service_completed_successfully",
                )

    def test_production_uses_only_prebuilt_migration_image(self) -> None:
        _, compose = load_environment("production")
        self.assertNotIn("build", compose["services"]["migration"])
        self.assertEqual(compose["services"]["migration"]["pull_policy"], "always")

    def test_initial_revision_enables_pgvector(self) -> None:
        revision = ROOT / "migrations" / "versions" / "20260714_0001_enable_pgvector.py"
        content = revision.read_text(encoding="utf-8")
        self.assertIn('revision: str = "20260714_0001"', content)
        self.assertIn("CREATE EXTENSION IF NOT EXISTS vector", content)

    def test_memory_schema_revision_chain_ends_at_expected_head(self) -> None:
        revisions = sorted((ROOT / "migrations" / "versions").glob("20260714_*.py"))
        self.assertEqual(
            [path.stem for path in revisions],
            [
                "20260714_0001_enable_pgvector",
                "20260714_0002_create_memory_tables",
                "20260714_0003_create_memory_indexes",
                "20260714_0004_add_memory_outbox",
            ],
        )

    def test_alembic_rejects_inline_migration_database_url(self) -> None:
        environment = os.environ.copy()
        environment["MEGURI_MIGRATION_DATABASE_URL"] = (
            "postgresql+asyncpg://owner:forbidden@localhost/meguri"
        )
        environment.pop("MEGURI_MIGRATION_DATABASE_URL_FILE", None)
        completed = subprocess.run(
            [sys.executable, "-m", "alembic", "upgrade", "head", "--sql"],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("must not be supplied inline", completed.stderr)


class MigrationEntrypointTests(unittest.TestCase):
    def test_asyncpg_url_accepts_only_postgresql(self) -> None:
        self.assertEqual(
            entrypoint.asyncpg_url("postgresql+asyncpg://owner@example/db"),
            "postgresql://owner@example/db",
        )
        self.assertEqual(
            entrypoint.asyncpg_url("postgresql://owner@example/db"),
            "postgresql://owner@example/db",
        )
        with self.assertRaises(RuntimeError):
            entrypoint.asyncpg_url("sqlite:///tmp/meguri.db")

    def test_identifier_and_literal_quoting_fail_closed(self) -> None:
        self.assertEqual(entrypoint.quote_identifier("meguri_staging_app"), '"meguri_staging_app"')
        self.assertEqual(entrypoint.quote_literal("pa'ss"), "'pa''ss'")
        with self.assertRaises(RuntimeError):
            entrypoint.quote_identifier("app; DROP ROLE owner")

    def test_secret_loader_rejects_missing_and_empty_files(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                entrypoint.read_secret("MEGURI_TEST_SECRET_FILE")

        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / "secret.txt"
            secret.write_text("\n", encoding="utf-8")
            with mock.patch.dict(os.environ, {"MEGURI_TEST_SECRET_FILE": str(secret)}, clear=True):
                with self.assertRaises(RuntimeError):
                    entrypoint.read_secret("MEGURI_TEST_SECRET_FILE")


if __name__ == "__main__":
    unittest.main()
