# Memory recovery validation report

Date: 2026-07-14  
Expected revision: `20260714_0004`

## Result

- Validator implementation: **passed offline tests**.
- Live dev database validation: **not executed**; `MEGURI_TEST_DATABASE_URL` is absent.
- Staging backup restore rehearsal: **not executed**; the environment contract is now `implementation-complete-runtime-evidence-required` but provides no accessible restored target, archive or database URL.
- Production restore/write: **not authorized**.

The read-only validator checks all nine required table counts, same-item `current_version_id`, active-item version existence, ready-embedding content hashes, audit replay of create/supersede/delete/restore/hard-delete and expected Alembic revision. It now accepts an approved fixed-recall corpus, verifies expected memory and optional current-version IDs through the native provider, emits content-free case/count evidence and fails when `--require-fixed-recall` is set without a corpus or any case misses its threshold. The integration test automatically exercises these gates when a test database URL and optional corpus path are supplied.

This report does not claim real RPO, RTO, archive integrity, restored counts or fixed-recall stability because no restored target or approved corpus was provided. Those remain a staging gate, but the gate is now executable rather than prose-only.
