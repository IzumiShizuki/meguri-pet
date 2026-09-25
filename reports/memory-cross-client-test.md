# Cross-client identity and isolation evidence

Date: 2026-07-14

## Automated evidence

- Unit tests prove verified Website/AstrBot identities resolve to one unified user, unbound identities receive distinct HMAC opaque users, display names never merge identities, and environment/platform boundaries remain distinct.
- Runtime tests prove short context is keyed by `user_id + client_id + session_id`.
- Native chat tests prove body-supplied identity/session scope is discarded in favor of the authenticated Website/AstrBot/AIRI/Desktop principal, a cross-tenant principal is rejected, and an unverified principal performs neither formal recall nor candidate writes.
- A native PostgreSQL integration test binds Website, AstrBot and AIRI identities to one user and verifies three separate session-summary rows. It also verifies a different tenant cannot retrieve the same logical user's memory.

## Result

Unit/runtime isolation: **passed**. Native database cross-client test: **provided but skipped**, because `MEGURI_TEST_DATABASE_URL` is absent. Therefore cross-client behavior is implementation-complete but not yet staging-proven.
