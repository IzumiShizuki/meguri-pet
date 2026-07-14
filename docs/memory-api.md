# Authoritative memory API

The routes in this document operate only when `MEGURI_MEMORY_PROVIDER=native_pgvector`. Tenant and user scope are derived from an upstream-authenticated `ApiPrincipal`; request bodies cannot override them. In production, the authentication middleware verifies the platform credential, resolves it through `IdentityResolver`, converts the result with `ApiPrincipal.from_resolved_identity(...)`, and installs it in `request.state.meguri_principal`. Trusted identity headers are a development-only escape hatch enabled by `MEGURI_ALLOW_TRUSTED_IDENTITY_HEADERS=true`.

All writes require `X-Request-ID`. Candidate review, identity mutation and hard deletion require an administrator principal. Stable error details use `{code, message}` and never include SQL, connection URLs or provider exception text.

When the native provider serves `/v1/chat/respond` or `/v1/turns`, the server also replaces body-supplied `user_id`, `client_id` and `session_id` with the authenticated principal. The principal tenant must match the provider tenant, and only `website`, `astrbot`, `airi` and `desktop_pet` clients are accepted. A principal without a verified formal-memory binding still receives a text response, but both formal-memory recall and candidate writes are skipped.

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
| POST | `/v1/memories/{id}/feedback` | verified user | Record typed feedback, including false-recall evidence, against a caller-owned immutable version |
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

`query`, optional `canonical_key`, `limit`, typed memory filters, scopes, modes and token budget are accepted. Exact-vector input must contain 1024 floats and identify the configured embedding model/revision. When no vector is supplied, the native runtime generates one using the pinned local embedding adapter for hybrid/exact-vector modes; if the adapter is unavailable, hybrid search degrades to keyword/structured retrieval and records a failure metric. Normal recall returns only active, unexpired current versions in the authenticated tenant/user scope. Candidate, deleted and historical versions are excluded.

## Compatibility boundary

The pre-existing fake-provider routes remain for local framework compatibility. They are not staging/production-authoritative. Native mode rejects legacy list/export/review entry points, and legacy `upsert` only queues a candidate unless the explicit local compatibility flag `MEGURI_ALLOW_LEGACY_MEMORY_AUTO_APPROVAL=true` is set. With no explicit provider, a dev database configured through `MEGURI_DATABASE_URL_FILE` selects native pgvector; an unconfigured local checkout stays fake so it can boot. Staging and production require native pgvector, production legacy mutation is denied, inline database URLs are rejected, and the provider factory never promotes MemoryOS or Mem0 to authority.
