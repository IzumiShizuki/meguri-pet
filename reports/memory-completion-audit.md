# Authoritative memory completion audit

Date: 2026-07-14

Branch: `codex/feat/native-pgvector-memory`

## Audit outcome

The post-M-012 code audit closed the remaining locally actionable gaps without changing PostgreSQL/pgvector authority or the MemoryOS/Mem0 read-only boundaries. The implementation is locally coherent, but staging remains NO-GO until the environment team supplies a real database and isolated restored target.

| Area | Gap found | Closure | Local evidence | Live evidence |
|---|---|---|---|---|
| Runtime vectors | Native queries did not create embeddings automatically | Pinned lazy BGE-M3 adapter, automatic query embedding, executable outbox worker | Adapter/provider/worker tests and CLI smoke pass | Model cache and worker not staged |
| Candidate lifecycle | Legacy runtime could approve an LLM candidate directly | Runtime submits candidates; legacy auto-approval defaults off | Runtime/provider tests pass | Native DB workflow skipped |
| Conflict/dedup | Approval lacked vector similarity | Exact-vector candidate comparison augments structured/lexical rules | Semantic conflict test passes | Real BGE-M3/pgvector case skipped |
| Retrieval modes | Structured mode had no repository path | Canonical-key structured query added | Repository/service tests pass | Native SQL test skipped |
| Idempotency | Same request IDs could collide across users and concurrent duplicates could race | User-scoped operation hashes plus transaction advisory locks | Cross-user and SQL contract tests pass | Concurrent DB test skipped |
| Feedback | Existing feedback table had no write contract | Version-owned typed feedback API/service/repository and false-recall metric added | API/service tests pass | Native DB feedback test skipped |
| Identity | Native chat trusted body user/session values | Authenticated tenant/user/client/session derivation; unverified formal memory disabled | API/runtime tests pass | Cross-client DB test skipped |
| Recovery | Fixed recall was a prose-only staging requirement | Approved-corpus validator with required gate and optional current-version assertion | Unit and CLI tests pass | Restored DB/corpus unavailable |

## Verification snapshot

- Python: 126 collected, 119 passed, 7 skipped for missing `MEGURI_TEST_DATABASE_URL`.
- TypeScript protocol/renderer/AIRI/TTS/Website suite: 20 passed.
- Syntax/import compilation: passed for adapters, services, scripts and tests.
- Migration offline upgrade and downgrade: passed through revision `20260714_0004`.
- Synthetic exact snapshot is p50 16.233 ms, p95 18.533 ms, p99 21.858 ms, error rate 0% and recall@5 1.0; ANN/HNSW remains disabled.
- The optional SentenceTransformers dependency and pinned model cache are not installed in local `py314`; lazy-loader behavior is unit-tested, but real model execution remains a staging gate.
- No production data or third-party memory authority was mutated.

## External acceptance boundary

The seven skipped tests cover the native provider contract, complete lifecycle/feedback/isolation workflow, rollback atomicity, idempotent concurrency, cross-client identity/session isolation, embedding SQL and recovered-database validation. None can be honestly converted to a pass without a PostgreSQL + pgvector handoff. A staging backup, isolated restore, approved recall corpus, observed latency/error measurements and cleanup proof are still mandatory.

The environment branch's latest machine contract is `implementation-complete-runtime-evidence-required`. Its remaining handoff gates match this audit: native provider/recovery contracts, environment and user isolation, pinned embedding worker execution, exact-search latency/recall and RPO/RTO evidence.
