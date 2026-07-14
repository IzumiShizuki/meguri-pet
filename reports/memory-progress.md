# Authoritative memory implementation progress

Updated: 2026-07-14  
Branch: `codex/feat/native-pgvector-memory`

## Milestones

| ID | Status | Commit | Delivered | Verification / rollback |
|---|---|---|---|---|
| M-001 | Complete | `db997d4` | Strict domain enums, immutable version models, provider contracts, ORM model boundary | Unit model tests; revert commit only |
| M-002 | Complete | `eac8851` | Alembic revisions `20260714_0001` through `20260714_0004`, pgvector hard prerequisite, nine core tables plus idempotency, immutable/audit triggers, exact-first indexes and outbox | Migration contract tests; downgrade one revision at a time. Live PostgreSQL migration is blocked pending a test database handoff |
| M-003 | Complete | `a465fac` | Async SQLAlchemy unit of work, tenant/user-scoped CRUD, row locks, audit, idempotency and outbox | Repository unit tests; revert commit after schema rollback |
| M-004 | Complete | `9d9af9f` | Deterministic candidate policy, sensitive/transient/inference rejection, explicit approval and conflict rules | Policy/conflict tests; auto-approval remains disabled |
| M-005 | Complete | `256f92d` | BGE-M3 adapter contract, immutable revision/dimension checks and retry/dead-letter embedding outbox worker | Worker tests; stop worker and retain pending outbox rows for rollback |
| M-006 | Complete | `e861dbe` | Tenant-scoped structured, keyword and exact-vector retrieval with deterministic hybrid ranking and token budget | Retrieval/budget tests; HNSW remains absent by design |
| M-007 | Complete | `d229995` | Native PostgreSQL provider and opt-in runtime factory with compatibility facade | Provider tests and legacy suite; default provider remains fake unless explicitly enabled |
| M-008 | Complete | `ba69b02` | Verified identity bindings, HMAC opaque fallback identity, cross-client sharing and session isolation rules | Identity resolver tests; unverified identities cannot write formal memory |
| M-009 | Complete | `5ca5912` | Authenticated authoritative API, admin-only review/binding, server-derived tenant/user scope, stable sanitized errors, unlabelled metrics | API boundary tests plus full regression suite; disable with `MEGURI_MEMORY_PROVIDER=fake` |
| M-010 | Complete | `c8ccad4` | JSONL export with every immutable version, provenance and audit events; audited soft delete/restore; feature-flagged admin hard delete after mandatory soft delete and typed confirmation | Export/lifecycle/API tests; hard delete retains append-only audit evidence and is disabled unless `MEGURI_ALLOW_HARD_DELETE=true` |
| M-011 | Complete | `3cea9ce` | Existing MemoryOS read-only importer and Mem0 aggregate-only shadow evaluator | Offline adapter/import/shadow tests; no live third-party instance was mutated and shadow results have no runtime prompt path |
| M-012 | Implemented; staging evidence blocked | this commit | Read-only recovery validator, native workflow integration suite, deterministic exact/ANN harness, secret-file environment contract, pinned release metadata and delivery reports | Offline migration/recovery/benchmark tests pass. Live database, backup restore and fixed-corpus validation skip because no test/staging connection or restored target was handed off |

## Current gates

- No `memory-environment-handoff.md` or `MEGURI_TEST_DATABASE_URL` is available. A machine-readable environment contract was observed in the parallel environment worktree, but it is still marked `implementation-required-before-staging`; native PostgreSQL and recovery tests therefore skip and staging evidence is not claimed.
- No production data has been mutated. Native authority is opt-in; the application continues to use the fake compatibility provider by default.
- Exact vector search is the supported baseline. ANN/HNSW will not be enabled without benchmark evidence and a separately reviewed migration.
