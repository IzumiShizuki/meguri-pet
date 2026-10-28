## Context

Real service inspection found both owner IDs unset, no Todo operator binding, no writer credential files and default-off creation. The user has now selected an administrator account and authorized reading/configuring it. Existing plugin-side simulation does not exercise the actual Java writer. Luna's read-only review also found a response-body timeout gap in the synchronous JDK InputStream path; reproduce before repairing it.

## Goals / Non-Goals

Goals: activate private owner-scoped creation, prove HTTP deadline/receipt/idempotency behavior, keep secrets outside the repository, and clean the single synthetic account task.

Non-goals: changing website code/account privileges, resetting passwords, writing unrelated tasks, sending QQ test messages, migrating databases, or widening a private sender binding to a whole platform/group.

## Decisions

1. Read existing account configuration first, authenticate through the existing HTTPS API, and derive the user ID from the verified response. Never echo credentials or persist access tokens in reports. External files are dedicated, access-restricted, and mounted read-only for the Core service UID.
2. Derive the operator from the previously authorized actual private AstrBot log and adapter metadata. Keep destination and executor binding separate; do not guess bot account IDs or use a wildcard.
3. Add loopback HTTP fixtures with synthetic credential files for the actual writer/controller. Introduce a bounded configurable HTTP budget if needed to make real stalled-body tests deterministic. The bound covers full response body, respects the existing 1 MiB limit, cancels unfinished work, and preserves existing rejected/uncertain/confirmed journal semantics.
4. Back up exact Core container and AstrBot configuration before activation; reuse the current image unless tested source repairs require a new JAR. Verify health and ordinary conversations after replacement.
5. Exercise the actual adapter entry using a synthetic event with the approved private binding and a local send collector. Schedule the unique test Todo well in the future, verify its receipt and duplicate confirmation, then delete and verify absence using the same account. Do not generate an imminent reminder or invoke real AstrBot send APIs.
6. Actual Core uses global `auth-required=false`; enabling it solely for Todo could break existing loopback chat/callback clients. Add a privileged-scope verification boundary that always validates the configured shared token and owner/client/session headers, independent of the global filter switch, and use it only for Todo creation. Missing token fails closed with 503. Ordinary route authentication and the separately disabled reminder poller remain unchanged.
7. Live acceptance exposed a successful write rejected by the adapter because the website returns seconds and the draft contract requires minutes. Canonicalize only the verified receipt to minute precision for new, cached and reconciled writes; preserve the journal's website data and the adapter's strict field checks.

## Risks / Trade-offs

- Body timeout after server commit cannot prove absence of a write → persist uncertain before POST, retry by marker only.
- Account configuration has sensitive values → dedicated external files, limited ACL/mode, no stdout/token logs or source literals.
- One live task is briefly user-visible → unique marker/title, future due date, record its exact ID before cleanup and never delete by broad name matching.
- Service restart interrupts requests → stage/health check first, retain exact prior container/config and validate ordinary conversations afterward.

## Migration Plan

Verify account/identity; create restricted secret files; complete Java fixture tests and reproduced fixes; stage the Core and owner configuration; activate Core and adapter; run local-collector live acceptance and cleanup; record readiness and rollback evidence.
