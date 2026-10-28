## Context

The existing remote PostgreSQL 16 server is reachable using the owner's synced configuration. It contains `shizuki_app` and currently has no installed pgvector package. The memory migration head is `20260729_0007`; older handoff metadata is stale. Java and the desktop gateway already run locally; the Python memory bridge does not.

## Goals / Non-Goals

Goals: isolate Meguri storage and credentials, apply current migrations, preserve existing project data, and persist startup configuration.

Non-goals: new operating system support, new embedding or chat model configuration, importing old chat history, or changing user privacy consent.

## Decisions

- Use a dedicated `meguri_memory` database with separate migration owner and application roles. Read the existing administrator credential only during provisioning; do not mount it into Core.
- Install pgvector into the existing server PostgreSQL deployment when existing server management access is available. Do not replace the container or its volume. If server management access is unavailable, prepare the connection and startup wiring and clearly report this prerequisite.
- Store DSNs and the internal bridge token under `D:/environment/secrets/meguri`, with file ACLs for the local owner. Startup defaults to these files when present; explicit environment configuration takes precedence.
- Run formal text memory with embedding and reranking disabled. Keep the schema's pinned embedding revision as metadata; no embedding worker is required for text records and review.
- Run migrations only against the dedicated database. Enforce exact schema revision during runtime readiness, and check pgvector availability before attempting migration.

## Risks / Trade-offs

- Server pgvector package missing → require authenticated server management access and verify the package before migrations.
- Shared infrastructure → separate database/roles, no modification of the existing project's tables or role permissions.
- Credentials in error messages → bounded sanitized errors; secret files remain outside Git and outside command arguments.
- Text retrieval only → explicitly disable embedding/reranking and document pending semantic vector retrieval.

## Migration Plan

Inspect existing server deployment, provision isolated database/roles, install the extension package if possible, run Alembic head, grant app DML privileges, write protected external secrets, and start the bridge/Core. Inspect database and protected desktop status/list endpoints. On failure keep existing project data intact and report the exact prerequisite. Disable the bridge for application rollback; do not drop databases or downgrade schema.
