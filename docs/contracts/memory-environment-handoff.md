# Memory Agent environment handoff

## Database access

Dev and Staging use their own PostgreSQL + pgvector instances, database names,
roles, networks and volumes. Managed code reads only:

```text
MEGURI_DATABASE_URL_FILE=<environment app-role URL file>
MEGURI_ENV=dev|staging
MEGURI_TENANT_ID=meguri-<environment>
MEGURI_DATABASE_REVISION=20260714_0004
MEGURI_EMBEDDING_MODEL_REVISION=5617a9f61b028005a4858fdac845db406aefb181
MEGURI_MEMORY_PROVIDER=native_pgvector
```

The migration-owner URL is available only to the one-shot migration service
through `MEGURI_MIGRATION_DATABASE_URL_FILE`. It is never mounted into core.
Staging application mutation defaults to false.

## Test database creation

Integration tests use a dedicated environment URL supplied through a secret
file. Recovery targets must match `meguri_staging_restore_*`; the rehearsal
script creates the target from `template0`, restores, validates and force-drops
it on success or failure. Never point tests at `infra-postgres` or another
environment's database.

## Migration job

```text
docker compose --project-name meguri-staging --env-file <runtime.env> \
  -f ops/compose/compose.base.yaml -f ops/compose/compose.staging.yaml \
  run --rm migration upgrade head
```

The job creates/rotates the app role, runs Alembic, then grants only app
connect/schema/table/sequence permissions. Core depends on successful job
completion. The Manifest field is `database_revision`; runtime readiness also
queries `alembic_version` and requires an exact match.

## Embedding worker

Use the native memory worker with the same app-role secret, tenant identity,
database revision and pinned embedding revision. Stop workers before an
application rollback so pending outbox rows remain recoverable. Embedding
failure may leave retryable outbox work but must not mark a vector ready or
block text response.

## Backup and restore entrypoints

```text
python ops/scripts/backup_staging.py --env-file <runtime.env> \
  --output-dir <staging-backup-dir> [--compose <standalone-compose>]
python ops/scripts/rehearse_staging_restore.py --env-file <runtime.env> \
  --metadata <metadata.json> --target meguri_staging_restore_<suffix> \
  [--compose <standalone-compose>]
```

The workflow checks archive size/SHA-256, revision, pgvector, nine table counts,
active-memory count and active identity fingerprint. The 2026-07-15 non-empty
Staging rehearsal passed with one item/version/audit/outbox record. See
`reports/staging-restore-rehearsal.md`.

Production restore is not authorized by this handoff. Use forward-fix for
normal application rollback; schema downgrade or active-database replacement
requires stopped writers, a verified backup and a separate change window.
