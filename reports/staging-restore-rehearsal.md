# Staging restore rehearsal report

> Executed: 2026-07-15 (Asia/Shanghai)
>
> Environment: `meguri-staging`
>
> Production restore authorization: **blocked**

## Result

The isolated staging backup and fresh-database restore rehearsal passed. The
authoritative evidence is the second, non-empty rehearsal for release
`meguri-staging-20260715-r002`.

| Evidence | Value |
| --- | --- |
| Source database | `meguri_staging` in `meguri-staging-postgres-1` |
| Temporary target | `meguri_staging_restore_20260715_0154` |
| Archive | `20260714T175633855519Z_meguri-staging-20260715-r002.dump` |
| Archive bytes | `35,922` |
| Archive SHA-256 | `1bfdaab8ecc9eae974e723faf8eec78954bde349cabc1a5d21b9ce08367e33df` |
| Metadata SHA-256 | `8d23141c0cc7699b73ad7b6aa6abb40a6ae167c489f4d593d4a12bc8a120bc42` |
| PostgreSQL | `16.14 (Debian 16.14-1.pgdg12+1)` |
| pgvector | `0.8.5` |
| Alembic revision | `20260714_0004` |
| Backup elapsed | `9.985 s` |
| Restore/verify/cleanup elapsed | `10.974 s` |
| Restore status | `passed` |

The archive and metadata are stored both in the explicit TLS control-plane
backup directory and under `/opt/meguri/staging/backups`. Their archive digest
and byte count match after transfer.

## Domain validation

A deterministic synthetic staging record was created through the migration
owner solely for recovery evidence. The source and restored database matched:

| Table/query | Result |
| --- | ---: |
| `memory_items` | 1 |
| `memory_versions` | 1 |
| `memory_audit_log` | 1 |
| `memory_outbox` | 1 |
| Other five authoritative memory tables | 0 |
| Active-memory count | 1 |
| Active-memory identity fingerprint | `8b44c99e722e7b1d056824a8721ad037` |

The restore workflow also checked archive size and SHA-256 before mutation,
the Alembic revision, pgvector presence, all nine table counts, and the fixed
identity query. The temporary database was force-dropped after verification;
a final query returned zero `meguri_staging_restore_*` databases.

## Recovery objectives

- Staging RTO evidence: `10.974 s` for create, restore, validate, and cleanup
  on this small logical archive.
- Staging RPO policy: pre-deploy on-demand logical backup. This rehearsal
  captured the current committed fixture; observed backup completion was
  `9.985 s`. It is not a production RPO commitment.
- Production RPO/RTO remain undefined and blocked pending a separate approved
  production recovery exercise.

## Commands

```text
python ops/scripts/backup_staging.py --env-file <r002>/runtime.env \
  --output-dir <control-plane-backups> --compose <standalone-compose>
python ops/scripts/rehearse_staging_restore.py --env-file <r002>/runtime.env \
  --metadata <r002-metadata> --target meguri_staging_restore_20260715_0154 \
  --compose <standalone-compose>
python -m unittest -v tests.test_postgres_backup
```

Seven backup/restore tests pass, including checksum rejection, pgvector
failure cleanup, count-mismatch cleanup, scoped target names, and remote
control-plane paths. No password, database URL, token, or key is recorded here.
