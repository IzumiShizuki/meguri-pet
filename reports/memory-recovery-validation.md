# Memory recovery validation report

Date: 2026-07-14  
Expected revision: `20260714_0004`

## Result

- Validator implementation: **passed offline tests**.
- Live dev database validation: **passed** against an isolated loopback PostgreSQL + pgvector container.
- Staging backup restore rehearsal: **not executed**; the environment contract is now `implementation-complete-runtime-evidence-required` but provides no accessible restored target, archive or database URL.
- Production restore/write: **not authorized**.

The read-only validator checks all nine required table counts, same-item `current_version_id`, active-item version existence, ready-embedding content hashes, audit replay of create/supersede/delete/restore/hard-delete and expected Alembic revision. It now accepts an approved fixed-recall corpus, verifies expected memory and optional current-version IDs through the native provider, emits content-free case/count evidence and fails when `--require-fixed-recall` is set without a corpus or any case misses its threshold. The integration test automatically exercises these gates when a test database URL and optional corpus path are supplied.

The live-dev validator run at `2026-07-15T00:07:44+08:00` reported:

| Check | Result |
|---|---:|
| `identity_bindings` | 12 |
| `memory_candidates` | 20 |
| `memory_items` | 12 |
| `memory_versions` | 20 |
| `memory_embeddings` | 20 |
| `memory_feedback` | 4 |
| `session_summaries` | 12 |
| `memory_audit_log` | 80 |
| `memory_outbox` | 20 |
| Invalid current versions | 0 |
| Invalid active items | 0 |
| Embedding hash mismatches | 0 |
| Audit replay mismatches | 0 |

Fixed recall corpus `live-dev-ready-embedding-20260714` contained one exact-vector case. It matched one of one expected current versions, for recall@k `1.0`, with no errors.

This report still does not claim staging RPO, staging RTO, archive integrity or restored staging counts because no approved staging backup/archive/restored target was provided. Those remain a staging gate, but the gate is now executable and has passed against the local live-dev database.
