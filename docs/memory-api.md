# Authoritative memory API

The routes in this document operate only when `MEGURI_MEMORY_PROVIDER=native_pgvector`. Tenant and user scope are derived from an upstream-authenticated `ApiPrincipal`; request bodies cannot override them. In production, the principal must be installed in `request.state.meguri_principal` by the authentication layer. Trusted identity headers are a development-only escape hatch enabled by `MEGURI_ALLOW_TRUSTED_IDENTITY_HEADERS=true`.

All writes require `X-Request-ID`. Candidate review, identity mutation and hard deletion require an administrator principal. Stable error details use `{code, message}` and never include SQL, connection URLs or provider exception text.

## Routes

| Method | Route | Permission | Purpose |
|---|---|---|---|
| POST | `/v1/memory/candidates` | verified user | Create a validated candidate; never directly creates active memory |
| GET | `/v1/memory/candidates` | verified user | List the caller's review candidates, optionally filtered by typed status |
| POST | `/v1/memory/candidates/{id}/approve` | admin | Approve through policy/conflict/transaction flow |
| POST | `/v1/memory/candidates/{id}/reject` | admin | Reject with an audited reason |
| POST | `/v1/memories/search` | verified user | Structured, keyword, exact-vector or hybrid search of active current versions |
| GET | `/v1/memories/{id}` | verified user | Get one caller-owned memory item |
| POST | `/v1/memories/{id}/supersede` | verified user | Append a new immutable version and move the current pointer |
| DELETE | `/v1/memories/{id}` | verified user | Audited soft delete |
| POST | `/v1/memories/{id}/restore` | verified user | Restore a soft-deleted item |
| POST | `/v1/memories/export` | verified user | Download `application/x-ndjson` with metadata, items, every version and audit events |
| GET | `/v1/identity-bindings` | authenticated user | List the caller's bindings |
| POST | `/v1/identity-bindings` | admin | Create a verified binding |
| DELETE | `/v1/identity-bindings/{id}` | admin | Unbind without deleting user memory |
| POST | `/v1/admin/memories/{id}/hard-delete` | admin + feature flag | Physically erase a previously soft-deleted aggregate while retaining audit evidence |
| GET | `/metrics` | deployment-controlled | Prometheus text metrics without user/session/content labels |

The hard-delete body requires `user_id`, `reason` and `confirmation="HARD_DELETE:{memory_id}"`. The server additionally requires `MEGURI_ALLOW_HARD_DELETE=true`; it is false by default.

## Search contract

`query`, `limit`, typed memory filters, scopes, modes and token budget are accepted. Exact-vector input must contain 1024 floats and identify the configured embedding model/revision. Normal recall returns only active, unexpired current versions in the authenticated tenant/user scope. Candidate, deleted and historical versions are excluded.

## Compatibility boundary

The pre-existing fake-provider routes remain for local framework compatibility. They are not production-authoritative. With no explicit provider, a configured dev database selects native pgvector; an unconfigured local checkout stays fake so it can boot. Production legacy mutation is denied, and the provider factory never promotes MemoryOS or Mem0 to authority.
