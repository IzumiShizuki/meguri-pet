## 1. Server prerequisites

- [x] 1.1 Inspect the existing server database and PostgreSQL deployment without recording credentials in repository artifacts.
- [x] 1.2 Install or confirm pgvector, provision isolated Meguri roles/database, and apply the current migration head.

## 2. Durable startup

- [x] 2.1 Store protected external application/migration DSNs and the internal bridge token.
- [x] 2.2 Load defaults and explicit overrides in the Windows bridge/Core launchers with exact schema and text retrieval configuration.

## 3. Runtime handoff

- [x] 3.1 Start the relevant services and inspect protected readiness/list endpoints against the real database.
- [x] 3.2 Review implementation against OpenSpec and publish a secret-free deployment feedback report with limitations.
