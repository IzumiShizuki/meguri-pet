"""Provision the environment app role and run Alembic without logging secrets."""

from __future__ import annotations

import asyncio
import os
import re
import subprocess
import sys
from pathlib import Path

import asyncpg


IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]{0,62}$")


def read_secret(variable: str) -> str:
    path = os.getenv(variable)
    if not path:
        raise RuntimeError(f"{variable} is required")
    value = Path(path).read_text(encoding="utf-8").strip()
    if not value:
        raise RuntimeError(f"{variable} points to an empty file")
    return value


def app_user() -> str:
    value = os.getenv("MEGURI_POSTGRES_APP_USER", "")
    if IDENTIFIER.fullmatch(value) is None:
        raise RuntimeError("MEGURI_POSTGRES_APP_USER must be a safe PostgreSQL identifier")
    return value


def asyncpg_url(value: str) -> str:
    if value.startswith("postgresql+asyncpg://"):
        return value.replace("postgresql+asyncpg://", "postgresql://", 1)
    if value.startswith("postgresql://"):
        return value
    raise RuntimeError("migration database URL must use PostgreSQL")


def quote_identifier(value: str) -> str:
    if IDENTIFIER.fullmatch(value) is None:
        raise RuntimeError("unsafe PostgreSQL identifier")
    return f'"{value}"'


def quote_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


async def ensure_app_role(database_url: str, username: str, password: str) -> None:
    connection = await asyncpg.connect(asyncpg_url(database_url))
    try:
        exists = await connection.fetchval("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)", username)
        role = quote_identifier(username)
        secret = quote_literal(password)
        if exists:
            await connection.execute(f"ALTER ROLE {role} LOGIN PASSWORD {secret} NOSUPERUSER NOCREATEDB NOCREATEROLE")
        else:
            await connection.execute(f"CREATE ROLE {role} LOGIN PASSWORD {secret} NOSUPERUSER NOCREATEDB NOCREATEROLE")
    finally:
        await connection.close()


async def grant_app_permissions(database_url: str, username: str) -> None:
    connection = await asyncpg.connect(asyncpg_url(database_url))
    try:
        database = await connection.fetchval("SELECT current_database()")
        role = quote_identifier(username)
        database_identifier = quote_identifier(str(database))
        statements = (
            f"GRANT CONNECT ON DATABASE {database_identifier} TO {role}",
            f"GRANT USAGE ON SCHEMA public TO {role}",
            f"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {role}",
            f"GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO {role}",
            f"ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {role}",
            f"ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO {role}",
        )
        for statement in statements:
            await connection.execute(statement)
    finally:
        await connection.close()


def run_alembic(arguments: list[str]) -> None:
    if not arguments:
        arguments = ["upgrade", "head"]
    completed = subprocess.run(
        [sys.executable, "-m", "alembic", "-c", "/app/alembic.ini", *arguments],
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"Alembic failed with exit code {completed.returncode}")


def main(argv: list[str] | None = None) -> int:
    try:
        database_url = read_secret("MEGURI_MIGRATION_DATABASE_URL_FILE")
        username = app_user()
        password = read_secret("MEGURI_POSTGRES_APP_PASSWORD_FILE")
        asyncio.run(ensure_app_role(database_url, username, password))
        run_alembic(list(argv if argv is not None else sys.argv[1:]))
        asyncio.run(grant_app_permissions(database_url, username))
    except (OSError, RuntimeError, asyncpg.PostgresError) as exc:
        print(f"migration_job_failed: {exc}", file=sys.stderr)
        return 1
    print("migration_job_passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
