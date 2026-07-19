# Authoritative memory database schema

Schema head: `20260714_0004`. PostgreSQL with pgvector is a hard prerequisite; migration `20260714_0001` fails explicitly if the extension cannot be created. No HNSW index exists in the baseline.

| Table | Role | Important constraints |
|---|---|---|
| `identity_bindings` | Platform identity to unified user | One active tenant/platform/platform-user binding; unbind is audited |
| `memory_candidates` | Pre-authority review queue | Typed status, memory type, sensitivity and source kind; provenance retained |
| `memory_items` | Memory aggregate/current pointer | Tenant/user scope; typed visibility; current version must belong to the same item |
| `memory_versions` | Immutable content chain | Unique `(memory_id, version_no)`; database trigger rejects update/delete |
| `memory_embeddings` | Version/model/revision vectors | Unique version/model/revision; fixed dimension 1024; content SHA-256 |
| `memory_feedback` | Recall and correction feedback | Tied to the exact item and version that was shown |
| `session_summaries` | Short-context rollups | Unique tenant/user/client/session key |
| `memory_audit_log` | Mutation evidence | Append-only trigger; request, actor, aggregate and sanitized details |
| `memory_outbox` | Reliable embedding work | Transactional enqueue and `FOR UPDATE SKIP LOCKED` claims |
| `memory_idempotency` | Replayed write result | Unique tenant/operation/request ID |

## Migration chain

1. `20260714_0001`: enable/require pgvector.
2. `20260714_0002`: create domain tables, constraints and immutable/append-only triggers.
3. `20260714_0003`: exact-first relational and full-text indexes; deliberately no ANN index.
4. `20260714_0004`: add transactional outbox.

Offline upgrade and downgrade SQL are covered by migration tests. Runtime upgrade must use the migration-role URL from `MEGURI_MIGRATION_DATABASE_URL_FILE`; staging and production do not accept a plaintext URL environment variable. Roll back one revision at a time after stopping writers/workers and taking a verified backup. Downgrading to base removes all memory tables and pgvector, so it is destructive and must never be used as an application rollback shortcut.
