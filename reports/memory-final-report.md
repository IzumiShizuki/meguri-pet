# Authoritative memory service delivery report

Date: 2026-07-14  
Integrated branch: `feat/environment-isolation`
Schema revision: `20260714_0004`  
Embedding: `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, 1024 dimensions  
Data build: `meguri_v2_02c3db0c507d7c2d`

## Outcome

M-001 through M-011 are implemented and locally verified. M-012 code, tests, benchmark harness, release metadata and recovery validator are implemented, but its live acceptance evidence is blocked because no dev/staging PostgreSQL URL, backup archive or isolated restored target was handed off. The authoritative memory service must **not** be declared complete or admitted to Staging yet.

## Milestone commits

| Task | Commit |
|---|---|
| Baseline | `b3a1fd8` |
| M-001 | `db997d4` |
| M-002 | `eac8851` |
| M-003 | `a465fac` |
| M-004 | `9d9af9f` |
| M-005 | `256f92d` |
| M-006 | `e861dbe` |
| M-007 | `d229995` |
| M-008 | `ba69b02` |
| M-009 | `5ca5912` |
| M-010 | `c8ccad4` |
| M-011 | `3cea9ce` |
| M-012 | this report's commit |

## Implemented contract

- PostgreSQL + pgvector is the only formal long-term-memory authority; MemoryOS is read-only and Mem0 is shadow-only.
- Nine required domain tables plus idempotency, immutable version and append-only audit enforcement, exact-first indexes and transactional outbox are present.
- Candidate policy rejects credentials, sensitive inference, transient state, raw tool/web/screen content and other forbidden sources. Global auto-approval is off.
- Approval creates item/version/audit/outbox atomically. Supersede appends; soft delete/restore are audited; hard delete is a separate disabled-by-default administrator flow.
- Retrieval applies tenant/user/status/current-version/expiry filters before deterministic exact/keyword/hybrid ranking and token budgeting.
- Verified Website, AstrBot and AIRI identities share unified-user memory; unbound and cross-environment identities remain isolated. Session summaries remain client/session scoped.
- API scope is derived from the authenticated principal; writes require request ID; errors are stable and sanitized; metrics have no user/content/session labels.
- Dev selects native pgvector by default when a database secret file is configured; unconfigured local development remains bootable with fake memory. Staging and production require native pgvector, and inline database URLs are rejected. Memory-provider failures continue to produce text with an unavailable/degraded memory status.
- Exports are NDJSON containing items, all immutable versions/provenance and audit events.

## Verification commands and results

```text
python -m compileall -q adapters services scripts tests
python -m pytest -q
python -m alembic upgrade head --sql
python -m alembic downgrade head:base --sql
python scripts/benchmark_memory_retrieval.py --corpus-size 500 --queries 40 --dimension 1024 --top-k 5 --seed 20260714
```

- Dedicated Memory suite: 64 collected, 58 passed and 6 database-dependent cases skipped because `MEGURI_TEST_DATABASE_URL` is absent. The final integrated repository suite is recorded in `reports/environment-final.md`.
- Offline Alembic upgrade: base → `20260714_0004`, passed.
- Offline Alembic downgrade: `20260714_0004` → base, passed.
- Latest synthetic exact baseline: p50 18.177 ms, p95 22.637 ms, p99 22.978 ms, error rate 0%, recall@5 100% on 500 deterministic 1024-dimensional vectors. This is not PostgreSQL/network latency.
- ANN/HNSW: not enabled and not measured.

## Staging decision

**NO-GO.** Required missing evidence:

1. Run `alembic upgrade head` against an empty isolated PostgreSQL + pgvector test database, repeat upgrade, and execute a reviewed downgrade rehearsal.
2. Run native provider contract and workflow tests with `MEGURI_TEST_DATABASE_URL`.
3. Create a real staging backup, restore it into `meguri_staging_restore_*`, run `scripts/validate_memory_recovery.py`, record counts/checksums/RPO/RTO and prove cleanup.
4. Execute fixed recall cases after restore and measure PostgreSQL p50/p95/p99 and error rate.
5. Merge the environment contract/release-manifest work and set both database and embedding revisions to the values in `memory-release-metadata.json`.

## Production operations still prohibited

- Production mutation remains false unless the separate production approval variable and release gate are satisfied.
- Hard delete remains disabled unless explicitly enabled for an approved administrator erasure workflow.
- Production database migration/restore is not authorized by this branch.
- HNSW creation is prohibited without live exact-versus-ANN evidence and a new reviewed migration.
- MemoryOS writes and any Mem0 authority/prompt injection are prohibited.

## Rollback

For local development only, `MEGURI_MEMORY_PROVIDER=fake` can remove native memory without changing authoritative data. Staging and production must roll back to an approved native-pgvector last-good release; fake memory never counts as a successful managed-environment fallback. Stop embedding workers before application rollback so pending outbox rows remain recoverable. Roll back application commits independently from schema. Schema downgrade is destructive and requires a verified backup, stopped writers and an explicit database change window; never use `downgrade base` as a routine application rollback.

## Sources

- [Plan 16: PostgreSQL + pgvector authoritative memory service](https://app.notion.com/p/39da36365963818b904ad4960dd3addc)
- [Plan 15: environment isolation](https://app.notion.com/p/39da363659638157a494e897cedef86f)
- [Data and provenance rules](https://app.notion.com/p/39ba3636596381bb92e8dac2e4356576)
- [Database deployment and backup boundary](https://app.notion.com/p/39ba3636596381588204e4e7ef9b698c)
