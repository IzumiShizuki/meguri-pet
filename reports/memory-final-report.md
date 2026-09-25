# Authoritative memory service delivery report

Date: 2026-07-14  
Integrated branch: `feat/environment-isolation`
Schema revision: `20260714_0004`  
Embedding: `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, 1024 dimensions  
Data build: `meguri_v2_02c3db0c507d7c2d`

## Outcome

M-001 through M-012 are implemented and locally verified. The resumed audit ran the native contract, workflow, recovery and required fixed-recall gates against an isolated loopback PostgreSQL + pgvector dev container. This proves the local native path and recovery validator, but it is not an approved staging backup/restore rehearsal or release acceptance run. The authoritative memory service must **not** be admitted to Staging yet.

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
| M-012 | `9127f2d` |
| Post-M-012 hardening | `b77d4ab` |
| Restored fixed-recall gate | `26c8859` |

## Implemented contract

- PostgreSQL + pgvector is the only formal long-term-memory authority; MemoryOS is read-only and Mem0 is shadow-only.
- Nine required domain tables plus idempotency, immutable version and append-only audit enforcement, exact-first indexes and transactional outbox are present.
- Candidate policy rejects credentials, sensitive inference, transient state, raw tool/web/screen content and other forbidden sources. Global auto-approval is off.
- Runtime LLM outputs create pending or policy-rejected authoritative candidates; the compatibility `upsert` path cannot auto-approve unless an explicit local-only flag is enabled.
- Approval creates item/version/audit/outbox atomically. Supersede appends; soft delete/restore are audited; hard delete is a separate disabled-by-default administrator flow.
- Retrieval applies tenant/user/status/current-version/expiry filters before deterministic structured/exact-vector/keyword/hybrid ranking and token budgeting. Hybrid runtime queries generate BGE-M3 vectors from the pinned local cache and degrade to keyword/structured retrieval when embeddings are unavailable.
- Verified Website, AstrBot and AIRI identities share unified-user memory; unbound and cross-environment identities remain isolated. Session summaries remain client/session scoped.
- API and native chat scope are derived from the authenticated principal; tenant mismatch is denied and unverified principals cannot read or write formal memory. Writes require request ID, feedback is version-scoped, errors are stable and sanitized, and metrics have no user/content/session labels.
- Idempotent mutations, exports and summaries use tenant/operation/request keys plus transaction-scoped PostgreSQL advisory locks; user-scoped operations prevent same-request cross-user replay leakage.
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

- Integrated Python verification: 201 passed against the live loopback pgvector dev container with temporary read-only canonical data/asset junctions.
- Focused live native verification: 8 passed across provider contract, native workflow and recovery validation suites.
- Recovery validator: passed at 2026-07-15T00:07:44+08:00 with `database_revision=20260714_0004`, zero current-version, active-item, embedding-hash or audit-replay mismatches, and one required exact-vector fixed-recall case at recall@k 1.0.
- TypeScript protocol/renderer/AIRI/TTS/Website verification: 20 passed with temporary read-only canonical data/asset junctions.
- Offline Alembic upgrade: base to `20260714_0004`, passed.
- Offline Alembic downgrade: `20260714_0004` to base, passed.
- Latest synthetic exact snapshot: p50 14.949 ms, p95 15.521 ms, p99 16.337 ms, error rate 0%, recall@5 100% on 500 deterministic 1024-dimensional vectors. This is not PostgreSQL/network latency.
- Environment/release checks: Memory and LLM agent contracts, release manifest, environment isolation and exposure ledger passed. Blocked staging acceptance and production approval checks failed as designed.
- ANN/HNSW: not enabled and not measured.

## Staging decision

**NO-GO.** Required missing evidence:

1. Run the same schema/provider/recovery gates in an approved staging environment, not only the loopback dev container.
2. Create a real staging backup, restore it into `meguri_staging_restore_*`, run `scripts/validate_memory_recovery.py --recall-corpus ... --require-fixed-recall`, record counts/checksums/RPO/RTO and prove cleanup.
3. Preserve the validator's per-case/aggregate recall evidence and measure PostgreSQL p50/p95/p99 and error rate on staging data.
4. Install the optional embedding dependency in the release image, stage the pinned BGE-M3 revision in the local cache, start the supervised worker and prove a real model-backed exact-vector recall case. The live-dev recall case used a precomputed vector to validate restored pgvector data and does not replace model runtime acceptance.
5. Produce a non-placeholder staging Release Manifest with immutable image digests, approved server inventory evidence and a passing all-or-nothing staging acceptance artifact.

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
