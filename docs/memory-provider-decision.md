# Memory provider decision

Status: implementation decision updated 2026-07-28.

1. Native PostgreSQL/pgvector is the authoritative Memory provider. Its
   migration head is `20260728_0006` and its contract includes reviewed
   candidates, immutable versions, export/delete/restore, risk, merge policy,
   optimistic base version and transactional embedding outbox records.
2. `FakeMemoryProvider` remains a deterministic local and contract-test
   implementation. Passing fake-provider tests does not prove PostgreSQL
   production readiness.
3. Companion policy remains provider-independent and owns credential
   rejection, candidate review, deduplication, confidence/sensitivity gates,
   importance mapping and formal-memory authorization.
4. Existing MemoryOS is retained untouched as a non-authoritative shadow
   adapter. Its missing stable IDs, supersede and delete semantics still
   disqualify it as the authority.
5. Mem0 is not deployed.
6. PostgreSQL is intended to be the sole authority. File projection is still
   open and should default to one-way mirror/export unless bidirectional editing
   is explicitly selected and a three-way conflict workflow is implemented.

The application must continue operating in an explicit degraded mode when
Memory search or candidate creation fails. Formal memories are shared only by
a verified bound `user_id`; recent context remains isolated by
`user_id + client_id + session_id`.

Production authorization remains gated on real PostgreSQL migration, backup,
restore, concurrent-write and outbox-recovery evidence.
