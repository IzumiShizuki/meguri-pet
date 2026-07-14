"""Portable staging backups and isolated restore rehearsals."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import tempfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

from ops.deployment.release import BASE_COMPOSE, STAGING_COMPOSE, atomic_write_json
from ops.scripts.check_environment_isolation import read_env_file


SAFE_NAME = re.compile(r"^[a-z][a-z0-9_]{0,62}$")
SAFE_RELEASE = re.compile(r"^[A-Za-z0-9._-]+$")


class BackupError(RuntimeError):
    """A backup or restore validation failed."""


class Transport(Protocol):
    def text(self, command: list[str]) -> str: ...

    def stdout_to_file(self, command: list[str], output: Path) -> None: ...

    def file_to_stdin(self, command: list[str], source: Path) -> None: ...


class SubprocessTransport:
    def text(self, command: list[str]) -> str:
        completed = subprocess.run(command, capture_output=True, check=False)
        if completed.returncode != 0:
            raise BackupError(f"database command failed with exit code {completed.returncode}")
        return completed.stdout.decode("utf-8", errors="strict").strip()

    def stdout_to_file(self, command: list[str], output: Path) -> None:
        with output.open("wb") as stream:
            completed = subprocess.run(command, stdout=stream, stderr=subprocess.PIPE, check=False)
        if completed.returncode != 0:
            raise BackupError(f"pg_dump failed with exit code {completed.returncode}")

    def file_to_stdin(self, command: list[str], source: Path) -> None:
        with source.open("rb") as stream:
            completed = subprocess.run(command, stdin=stream, stderr=subprocess.PIPE, check=False)
        if completed.returncode != 0:
            raise BackupError(f"pg_restore failed with exit code {completed.returncode}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def timestamp() -> str:
    return datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")


class StagingDatabase:
    def __init__(
        self,
        env_file: Path,
        *,
        docker: str = "docker",
        compose: str | None = None,
        transport: Transport | None = None,
    ) -> None:
        if not env_file.is_absolute():
            raise BackupError("env file path must be absolute")
        self.env_file = env_file
        self.env = read_env_file(env_file)
        if self.env.get("MEGURI_ENV") != "staging":
            raise BackupError("backup workflow accepts staging only")
        if self.env.get("COMPOSE_PROJECT_NAME") != "meguri-staging":
            raise BackupError("COMPOSE_PROJECT_NAME must be meguri-staging")
        self.database = self.env.get("MEGURI_POSTGRES_DB", "")
        self.owner = self.env.get("MEGURI_POSTGRES_USER", "")
        if SAFE_NAME.fullmatch(self.database) is None or SAFE_NAME.fullmatch(self.owner) is None:
            raise BackupError("database and owner must be safe PostgreSQL identifiers")
        self.docker = docker
        self.compose_executable = compose
        self.transport = transport or SubprocessTransport()

    def compose(self) -> list[str]:
        prefix = [self.compose_executable] if self.compose_executable else [self.docker, "compose"]
        return prefix + [
            "--project-name",
            "meguri-staging",
            "--env-file",
            str(self.env_file),
            "-f",
            str(BASE_COMPOSE),
            "-f",
            str(STAGING_COMPOSE),
        ]

    def postgres(self, *arguments: str) -> list[str]:
        return self.compose() + ["exec", "-T", "postgres", *arguments]

    def query(self, sql: str, *, database: str | None = None) -> str:
        target = database or self.database
        if SAFE_NAME.fullmatch(target) is None:
            raise BackupError("unsafe query database identifier")
        return self.transport.text(
            self.postgres("psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-U", self.owner, "-d", target, "-c", sql)
        )

    def command(self, *arguments: str) -> str:
        return self.transport.text(self.postgres(*arguments))

    def dump(self, output: Path) -> None:
        command = self.postgres(
            "pg_dump",
            "-U",
            self.owner,
            "-d",
            self.database,
            "--format=custom",
            "--compress=6",
            "--no-owner",
            "--no-privileges",
        )
        self.transport.stdout_to_file(command, output)

    def restore(self, source: Path, target: str) -> None:
        if SAFE_NAME.fullmatch(target) is None:
            raise BackupError("unsafe restore database identifier")
        command = self.postgres(
            "pg_restore",
            "-U",
            self.owner,
            "-d",
            target,
            "--exit-on-error",
            "--no-owner",
            "--no-privileges",
        )
        self.transport.file_to_stdin(command, source)


def create_backup(database: StagingDatabase, output_dir: Path) -> Path:
    configured_dirs = [Path(database.env.get("MEGURI_BACKUP_DIR", ""))]
    control_plane_dir = database.env.get("MEGURI_CONTROL_PLANE_BACKUP_DIR", "").strip()
    if control_plane_dir:
        configured_dirs.append(Path(control_plane_dir))
    if output_dir.resolve() not in {path.resolve() for path in configured_dirs}:
        raise BackupError(
            "output directory must equal MEGURI_BACKUP_DIR or MEGURI_CONTROL_PLANE_BACKUP_DIR"
        )
    release_id = database.env.get("MEGURI_RELEASE_ID", "")
    if SAFE_RELEASE.fullmatch(release_id) is None:
        raise BackupError("MEGURI_RELEASE_ID is unsafe for a backup filename")
    output_dir.mkdir(parents=True, exist_ok=True)
    revision = database.query("SELECT version_num FROM alembic_version")
    server_version = database.query("SHOW server_version")
    archive = output_dir / f"{timestamp()}_{release_id}.dump"
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{archive.name}.", suffix=".tmp", dir=output_dir)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        database.dump(temporary)
        if temporary.stat().st_size == 0:
            raise BackupError("pg_dump produced an empty archive")
        os.chmod(temporary, 0o600)
        os.replace(temporary, archive)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    metadata = {
        "schema_version": 1,
        "environment": "staging",
        "release_id": release_id,
        "data_build_id": database.env.get("MEGURI_DATA_BUILD_ID"),
        "database": database.database,
        "database_revision": revision,
        "postgres_server_version": server_version,
        "archive_file": archive.name,
        "archive_bytes": archive.stat().st_size,
        "archive_sha256": sha256_file(archive),
        "created_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "restore_rehearsal": {"status": "pending"},
    }
    metadata_path = archive.with_suffix(".metadata.json")
    atomic_write_json(metadata_path, metadata)
    os.chmod(metadata_path, 0o600)
    return metadata_path


def rehearse_restore(database: StagingDatabase, metadata_path: Path, target: str) -> dict[str, Any]:
    if not target.startswith("meguri_staging_restore_") or SAFE_NAME.fullmatch(target) is None:
        raise BackupError("restore target must be a safe meguri_staging_restore_* database")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if not isinstance(metadata, dict) or metadata.get("environment") != "staging":
        raise BackupError("backup metadata is invalid or not staging")
    archive_name = str(metadata.get("archive_file", ""))
    if Path(archive_name).name != archive_name or not archive_name.endswith(".dump"):
        raise BackupError("backup archive filename is unsafe")
    archive = metadata_path.parent / archive_name
    if not archive.is_file():
        raise BackupError("backup archive is missing")
    if archive.stat().st_size != metadata.get("archive_bytes"):
        raise BackupError("backup archive size does not match metadata")
    if sha256_file(archive) != metadata.get("archive_sha256"):
        raise BackupError("backup archive checksum does not match metadata")

    created = False
    try:
        database.command("createdb", "-U", database.owner, "--template=template0", target)
        created = True
        database.restore(archive, target)
        revision = database.query("SELECT version_num FROM alembic_version", database=target)
        vector_version = database.query(
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
            database=target,
        )
        if revision != metadata.get("database_revision"):
            raise BackupError("restored Alembic revision does not match backup metadata")
        if not vector_version:
            raise BackupError("restored database does not contain pgvector")
    finally:
        if created:
            database.command("dropdb", "-U", database.owner, "--force", target)

    completed = {
        **metadata,
        "restore_rehearsal": {
            "status": "passed",
            "target_database": target,
            "database_revision": revision,
            "pgvector_version": vector_version,
            "completed_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        },
    }
    atomic_write_json(metadata_path, completed)
    os.chmod(metadata_path, 0o600)
    return completed
