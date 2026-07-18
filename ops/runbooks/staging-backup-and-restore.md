# Staging PostgreSQL backup and restore rehearsal

This procedure is limited to the isolated `meguri-staging` PostgreSQL
container, its named volume, and `/opt/meguri/staging/backups`. It must never be
run against `infra-postgres`, a dev/production env file, or another project's
volume.

## Create a backup

```bash
python ops/scripts/backup_staging.py \
  --env-file /opt/meguri/staging/releases/<release-id>/runtime.env \
  --output-dir /opt/meguri/staging/backups
```

The command uses `pg_dump --format=custom --no-owner --no-privileges` inside
the staging PostgreSQL container. It does not read a password into the host
process. The archive and metadata use mode `0600`; metadata records SHA-256,
size, release/data build, PostgreSQL version, and Alembic revision. Backups are
never deleted automatically.

For a TLS Docker control plane, set
`MEGURI_CONTROL_PLANE_BACKUP_DIR=<absolute-local-staging-backup-dir>` in the
local release env and pass the standalone Compose binary with `--compose`.
`pg_dump` and `pg_restore` still execute inside the isolated staging database
container; only the checksummed archive stream and metadata live in the
explicit control-plane directory. Copy the completed archive and metadata to
`/opt/meguri/staging/backups` without changing either checksum.

## Rehearse restore safely

Choose a unique temporary name matching `meguri_staging_restore_*`:

```bash
python ops/scripts/rehearse_staging_restore.py \
  --env-file /opt/meguri/staging/releases/<release-id>/runtime.env \
  --metadata /opt/meguri/staging/backups/<backup>.metadata.json \
  --target meguri_staging_restore_YYYYMMDDHHMMSS
```

The rehearsal rejects a changed size or checksum before database mutation,
creates only the named temporary database, restores with owner/ACL disabled,
checks the Alembic revision and pgvector extension, compares all nine core
memory-table counts plus the fixed active-memory count and identity fingerprint,
and drops the temporary database with `--force` even when validation fails. A
successful rehearsal is written back to the metadata atomically.

## Recovery boundary

These scripts deliberately do not overwrite the active database. Replacing an
active staging database requires a maintenance window, stopped core, a newly
verified backup, successful restore rehearsal, an explicit recovery command,
and post-restore readiness/integration checks. Production restore is not
authorized by this runbook.

Never use `docker compose down -v`, delete a named volume, edit the archive,
restore into `infra-postgres`, or assume a successful `pg_dump` is recoverable
without the rehearsal result.
