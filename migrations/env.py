from __future__ import annotations

import asyncio
import os
from logging.config import fileConfig
from pathlib import Path

from alembic import context
from sqlalchemy import pool
from sqlalchemy.ext.asyncio import async_engine_from_config

from services.meguri_core.memory_service.orm import Base

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def database_url() -> str:
    direct = os.getenv("MEGURI_MIGRATION_DATABASE_URL")
    file_name = os.getenv("MEGURI_MIGRATION_DATABASE_URL_FILE")
    environment = os.getenv("MEGURI_ENV", "dev")
    if direct and file_name:
        raise RuntimeError("set only one of MEGURI_MIGRATION_DATABASE_URL or MEGURI_MIGRATION_DATABASE_URL_FILE")
    if environment in {"staging", "production"} and direct:
        raise RuntimeError("staging and production migrations require MEGURI_MIGRATION_DATABASE_URL_FILE")
    if file_name:
        secret_path = Path(file_name)
        if not secret_path.is_absolute():
            raise RuntimeError("migration database URL file path must be absolute")
        if not secret_path.is_file():
            raise RuntimeError("migration database URL file is not readable")
        if secret_path.stat().st_size > 8192:
            raise RuntimeError("migration database URL file is unexpectedly large")
        direct = secret_path.read_text(encoding="utf-8").strip()
    if not direct and context.is_offline_mode():
        direct = config.get_main_option("sqlalchemy.url")
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
        transaction_per_migration=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection) -> None:
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        compare_type=True,
        transaction_per_migration=True,
    )
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
