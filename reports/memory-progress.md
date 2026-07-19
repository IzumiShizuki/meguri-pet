# Authoritative memory implementation progress

Updated: 2026-07-14  
Integrated branch: `feat/environment-isolation`

## Milestones

| ID | Status | Commit | Delivered | Verification / rollback |
|---|---|---|---|---|
| M-001 | Complete | `db997d4` | Strict domain enums, immutable version models, provider contracts, ORM model boundary | Unit model tests; revert commit only |
| M-002 | Complete | `eac8851` | Alembic revisions `20260714_0001` through `20260714_0004`, pgvector hard prerequisite, nine core tables plus idempotency, immutable/audit triggers, exact-first indexes and outbox | Migration contract tests; downgrade one revision at a time. Offline upgrade/downgrade SQL passed; live dev pgvector tests passed in the loopback container |
| M-003 | Complete | `a465fac` | Async SQLAlchemy unit of work, tenant/user-scoped CRUD, row locks, audit, idempotency and outbox | Repository unit tests; revert commit after schema rollback |
| M-004 | Complete | `9d9af9f` | Deterministic candidate policy, sensitive/transient/inference rejection, explicit approval and conflict rules | Policy/conflict tests; auto-approval remains disabled |
| M-005 | Complete | `256f92d`, `b77d4ab` | Lazy local-files-only SentenceTransformers BGE-M3 adapter, immutable revision/dimension checks, executable one-batch worker and retry/dead-letter outbox | Adapter/worker tests and CLI smoke; stop worker and retain pending outbox rows for rollback |
| M-006 | Complete | `e861dbe`, `b77d4ab` | Tenant-scoped structured, keyword and exact-vector retrieval, automatic pinned query embedding, deterministic hybrid ranking and token budget | Retrieval/budget/provider tests; graceful keyword fallback; HNSW remains absent by design |
| M-007 | Complete | `d229995`, `b77d4ab` | Native PostgreSQL provider and opt-in runtime factory with compatibility facade that queues runtime candidates instead of auto-approving | Provider/runtime tests and legacy suite; auto-approval requires an explicit local compatibility flag |
| M-008 | Complete | `ba69b02`, `b77d4ab` | Verified identity bindings, HMAC opaque fallback identity, cross-client sharing and session isolation rules | Identity resolver/runtime tests; unverified identities cannot read or write formal memory |
| M-009 | Complete | `5ca5912`, `b77d4ab` | Authenticated authoritative API, admin-only review/binding, server-derived tenant/user/client/session scope, typed feedback, stable sanitized errors and unlabelled metrics | API boundary tests plus full regression suite; native chat rejects tenant mismatch and untrusted body scope |
| M-010 | Complete | `c8ccad4` | JSONL export with every immutable version, provenance and audit events; audited soft delete/restore; feature-flagged admin hard delete after mandatory soft delete and typed confirmation | Export/lifecycle/API tests; hard delete retains append-only audit evidence and is disabled unless `MEGURI_ALLOW_HARD_DELETE=true` |
| M-011 | Complete | `3cea9ce` | Existing MemoryOS read-only importer and Mem0 aggregate-only shadow evaluator | Offline adapter/import/shadow tests; no live third-party instance was mutated and shadow results have no runtime prompt path |
| M-012 | Live-dev verified; staging evidence blocked | `9127f2d`, `b77d4ab`, `26c8859` | Read-only recovery and required fixed-recall validator, native workflow integration suite, deterministic exact/ANN harness, secret-file environment contract, pinned release metadata and delivery reports | Live dev pgvector contract/workflow/recovery tests passed; required exact-vector fixed-recall passed with recall@k 1.0. Staging backup/restore and release acceptance remain blocked |

## Current gates

- The resumed audit found a live loopback dev PostgreSQL + pgvector container and ran the native database, recovery and fixed-recall gates successfully. This is local dev evidence, not approved staging evidence.
- Integrated verification after the environment/hardening merge passed: full Python suite against live dev pgvector, full TypeScript suite, offline Alembic upgrade/downgrade, exact benchmark, environment contract, release manifest, isolation and exposure checks.
- The local `py314` environment does not contain the optional `sentence-transformers` package or a staged BGE-M3 cache. Adapter behavior is covered with an injected loader, but the release image/cache/worker must be provisioned before vector acceptance.
- No production data has been mutated. Unconfigured development remains bootable with fake memory; staging and production fail closed unless native pgvector and its file secrets are configured.
- Exact vector search is the supported baseline. ANN/HNSW will not be enabled without benchmark evidence and a separately reviewed migration.
