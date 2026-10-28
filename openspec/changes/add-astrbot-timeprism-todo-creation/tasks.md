## 1. AstrBot reminder drafting

- [x] 1.1 Add a bounded Asia/Shanghai one-time reminder parser that resolves omitted dates and bare hours to the nearest future occurrence and safely clarifies unsupported or past explicit times.
- [x] 1.2 Add an atomic, bounded AstrBot draft store with expiry, opaque sender binding, stable request IDs, and preview/edit/cancel/confirm states.
- [x] 1.3 Add default-off configuration for the exact private owner sender allowlist and draft store, then intercept supported reminder requests and `/meguri todo` draft commands before normal Core routing while preserving plugin-owned commands.
- [x] 1.4 Add the authenticated Core client request for confirmed drafts and return the verified result or a recoverable failure to the owner.

## 2. Core TimePrism writer

- [x] 2.1 Add Core writer configuration for owner credential file paths and validate the configured owner binding and fail-closed behavior.
- [x] 2.2 Implement the authenticated owner-scoped TimePrism Todo creation endpoint and server-side login/write flow using the Desktop reminder payload and response validation.
- [x] 2.3 Add a durable idempotency journal and request-marker reconciliation so repeated, conflicting, or uncertain writes cannot create duplicates.

## 3. Operator documentation and implementation review

- [x] 3.1 Document the owner sender allowlist, confirmation commands, default-off flag, external Core credential files, and the required restart/configuration steps without including secrets or changing a live deployment.
- [x] 3.2 Review the resulting changes against the spec and run strict OpenSpec validation; leave automated tests unadded and unrun unless separately requested.
