# Staging restore rehearsal report

## Current result

- Runtime staging rehearsal: **not executed**.
- Repository simulation: **passed** on 2026-07-14.
- Production restore authorization: **blocked**.

No Meguri staging container or volume existed in the read-only server baseline,
and the current process does not have the server-side release directory and
secret-file access needed to create an isolated staging database safely.
Therefore this report does not claim a real backup, restore, RPO, or RTO result.

## Verified locally

Five automated tests exercise the backup/restore control flow with an injected
database transport:

- custom archive plus checksum/size/revision metadata;
- restore into a `meguri_staging_restore_*` database;
- Alembic revision and pgvector validation;
- forced cleanup after both success and injected validation failure;
- checksum and environment-scope rejection before mutation.

Command:

```text
python -m unittest -v tests.test_postgres_backup
```

Result: 5 passed.

## Required runtime evidence before promotion

Update this report with the archive metadata filename and digest, source and
temporary target database identities, start/end timestamps, observed archive
size, restored revision, pgvector version, row/count or domain integrity
checks once schema tables exist, cleanup proof, and measured RPO/RTO. Do not
copy passwords, database URLs, tokens, or other secret values into the report.
