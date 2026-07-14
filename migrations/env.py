from __future__ import annotations

import asyncio
import os
from logging.config import fileConfig
from pathlib import Path

from alembic import context
from sqlalchemy import pool
from sqlalchemy.ext.asyncio import async_engine_from_config


config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = None


def database_url() -> str:
    direct = os.getenv("MEGURI_MIGRATION_DATABASE_URL")
    file_name = os.getenv("MEGURI_MIGRATION_DATABASE_URL_FILE")
    if direct and file_name:
        raise RuntimeError("set only one of MEGURI_MIGRATION_DATABASE_URL or MEGURI_MIGRATION_DATABASE_URL_FILE")
    if file_name:
        direct = Path(file_name).read_text(encoding="utf-8").strip()
    if not direct:
        raise RuntimeError("migration database URL is required")
    if not direct.startswith(("postgresql+asyncpg://", "postgresql://")):
        raise RuntimeError("migration database URL must use PostgreSQL")
    if direct.startswith("postgresql://"):
        direct = direct.replace("postgresql://", "postgresql+asyncpg://", 1)
    return direct


def run_migrations_offline() -> None:
    context.configure(
        url=database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata, compare_type=True)
    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    configuration = config.get_section(config.config_ini_section, {})
    configuration["sqlalchemy.url"] = database_url()
    connectable = async_engine_from_config(
        configuration,
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)
    await connectable.dispose()


def run_migrations_online() -> None:
    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
