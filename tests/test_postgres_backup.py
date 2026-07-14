from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from ops.backup.postgres import (
    BackupError,
    StagingDatabase,
    create_backup,
    rehearse_restore,
)


class FakeTransport:
    def __init__(self, *, vector_version: str = "0.8.5") -> None:
        self.commands: list[list[str]] = []
        self.vector_version = vector_version

    def text(self, command: list[str]) -> str:
        self.commands.append(command)
        joined = " ".join(command)
        if "SELECT version_num FROM alembic_version" in joined:
            return "20260714_0004"
        if "SHOW server_version" in joined:
            return "16.9"
        if "SELECT extversion FROM pg_extension" in joined:
            return self.vector_version
        return ""

    def stdout_to_file(self, command: list[str], output: Path) -> None:
        self.commands.append(command)
        output.write_bytes(b"PGDMP\x01isolated-staging-archive")

    def file_to_stdin(self, command: list[str], source: Path) -> None:
        self.commands.append(command)
        if not source.read_bytes().startswith(b"PGDMP"):
            raise BackupError("invalid fake archive")


def env_file(root: Path, backup_dir: Path) -> Path:
    path = (root / "runtime.env").resolve()
    path.write_text(
        "\n".join(
            (
                "COMPOSE_PROJECT_NAME=meguri-staging",
                "MEGURI_ENV=staging",
                "MEGURI_RELEASE_ID=meguri-staging-r001",
                "MEGURI_DATA_BUILD_ID=meguri_v2_02c3db0c507d7c2d",
                "MEGURI_POSTGRES_DB=meguri_staging",
                "MEGURI_POSTGRES_USER=meguri_staging_migration",
                f"MEGURI_BACKUP_DIR={backup_dir.resolve()}",
            )
        )
        + "\n",
        encoding="utf-8",
    )
    return path


class PostgresBackupTests(unittest.TestCase):
    def test_backup_writes_archive_and_checksummed_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backups = root / "backups"
            transport = FakeTransport()
            database = StagingDatabase(env_file(root, backups), transport=transport)
            metadata_path = create_backup(database, backups)
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            archive = backups / metadata["archive_file"]

            self.assertTrue(archive.is_file())
            self.assertEqual(metadata["database_revision"], "20260714_0004")
            self.assertEqual(metadata["restore_rehearsal"], {"status": "pending"})
            self.assertEqual(metadata["archive_bytes"], archive.stat().st_size)
            self.assertTrue(any("pg_dump" in command for command in transport.commands))

    def test_restore_rehearsal_verifies_revision_and_pgvector_then_cleans_up(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backups = root / "backups"
            transport = FakeTransport()
            database = StagingDatabase(env_file(root, backups), transport=transport)
            metadata_path = create_backup(database, backups)
            result = rehearse_restore(database, metadata_path, "meguri_staging_restore_test")

            self.assertEqual(result["restore_rehearsal"]["status"], "passed")
            self.assertEqual(result["restore_rehearsal"]["pgvector_version"], "0.8.5")
            joined = [" ".join(command) for command in transport.commands]
            self.assertTrue(any("createdb" in command for command in joined))
            self.assertTrue(any("pg_restore" in command for command in joined))
            self.assertTrue(any("dropdb" in command and "--force" in command for command in joined))

    def test_restore_failure_still_drops_temporary_database(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backups = root / "backups"
            transport = FakeTransport(vector_version="")
            database = StagingDatabase(env_file(root, backups), transport=transport)
            metadata_path = create_backup(database, backups)
            with self.assertRaisesRegex(BackupError, "does not contain pgvector"):
                rehearse_restore(database, metadata_path, "meguri_staging_restore_fault")
            self.assertTrue(any("dropdb" in command for command in map(" ".join, transport.commands)))

    def test_checksum_mismatch_fails_before_database_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backups = root / "backups"
            transport = FakeTransport()
            database = StagingDatabase(env_file(root, backups), transport=transport)
            metadata_path = create_backup(database, backups)
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            (backups / metadata["archive_file"]).write_bytes(b"tampered-but-same-test-does-not-require-size")
            command_count = len(transport.commands)
            with self.assertRaisesRegex(BackupError, "size does not match|checksum does not match"):
                rehearse_restore(database, metadata_path, "meguri_staging_restore_tampered")
            self.assertEqual(len(transport.commands), command_count)

    def test_restore_target_and_backup_directory_are_environment_scoped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backups = root / "backups"
            database = StagingDatabase(env_file(root, backups), transport=FakeTransport())
            with self.assertRaisesRegex(BackupError, "MEGURI_BACKUP_DIR"):
                create_backup(database, root / "other")
            metadata_path = create_backup(database, backups)
            with self.assertRaisesRegex(BackupError, "meguri_staging_restore"):
                rehearse_restore(database, metadata_path, "meguri_staging")

            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            metadata["archive_file"] = "../other-project.dump"
            metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
            with self.assertRaisesRegex(BackupError, "filename is unsafe"):
                rehearse_restore(database, metadata_path, "meguri_staging_restore_safe")

    def test_remote_control_plane_has_explicit_compose_and_backup_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            remote_backups = root / "remote-backups"
            control_backups = root / "control-backups"
            env_path = env_file(root, remote_backups)
            env_path.write_text(
                env_path.read_text(encoding="utf-8")
                + f"MEGURI_CONTROL_PLANE_BACKUP_DIR={control_backups.resolve()}\n",
                encoding="utf-8",
            )
            transport = FakeTransport()
            database = StagingDatabase(
                env_path,
                compose="docker-compose",
                transport=transport,
            )
            metadata_path = create_backup(database, control_backups)

            self.assertTrue(metadata_path.is_file())
            self.assertTrue(all(command[0] == "docker-compose" for command in transport.commands))


if __name__ == "__main__":
    unittest.main()
