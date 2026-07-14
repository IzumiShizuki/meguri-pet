from __future__ import annotations

import asyncio
import os
from logging.config import fileConfig

from alembic import context
from sqlalchemy import pool
from sqlalchemy.ext.asyncio import async_engine_from_config

from services.meguri_core.memory_service.orm import Base
from services.meguri_core.memory_service.database import load_secret_text


config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

environment = os.getenv("MEGURI_ENV", "dev")
migration_url_file = os.getenv("MEGURI_MIGRATION_DATABASE_URL_FILE")
if environment in {"staging", "production"} and not migration_url_file:
    raise RuntimeError(
        "staging and production migrations require MEGURI_MIGRATION_DATABASE_URL_FILE"
    )
database_url = (
    load_secret_text(
        migration_url_file,
        variable="MEGURI_MIGRATION_DATABASE_URL_FILE",
    )
    if migration_url_file
    else os.getenv("MEGURI_DATABASE_URL")
)
if database_url:
    config.set_main_option("sqlalchemy.url", database_url)

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
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
    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
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
