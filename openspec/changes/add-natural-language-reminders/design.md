## Context

See proposal.md for motivation. ChatDock currently appends voice to drafts; TodoPanel sends a title and datetime-local to TimePrism. The authenticated Electron client sends a full Todo upsert and the local runtime synchronizes every 75 seconds and scans every 30 seconds. Core's durable inbox is an event receiver, not a scheduler; AstrBot's successful-send cursor is independent of receipt. All related specs currently live in unarchived changes. No repository-local or ancestor AGENTS.md was found; the user-provided global instructions apply.

Read-only production probing is performed before implementation. The public site is reachable, `/v3/api-docs` falls back to frontend HTML, and the Core reminder path requires authentication. These probes cannot establish zero-minute persistence or an offline worker. The local Shizuki companion change accepts zero-minute reminders, but local source is not deployment evidence.

## Goals / Non-Goals

**Goals:** Confirmed local reminder scheduling independent of the LLM, timezone-safe parsing, recoverable drafts, bounded IPC permissions, and server-acknowledged lifecycle controls.

**Non-Goals:** Deploying another repository/service, offline guarantees, recurring jobs, scheduled order checks, general action execution, or sending test QQ messages. Unsupported intents are blocked before any write. Future action support requires server-persistent scheduling, a capability allowlist, permission checks, immutable confirmation, idempotent execution receipts, failure states and separately confirmed external side effects.

## Decisions

### Shared deterministic parser and review controller

Use a dependency-free shared module for Chinese numerals, day parts, today/tomorrow/day-after, explicit dates, 24-hour times and minute/hour offsets. Return `none`, `clarify`, `unsupported`, or `ready`; only `ready` produces a confirmation model. Text send and voice-draft send share one controller; speech never creates automatically. A clarification card accepts replacement time or title. Relative dates are frozen at review, then revalidated for future time at confirmation. Date-only, bare 1–12 hours, recurrence, multiple intents, and foreign zones never guess. A model parser was considered but rejected because inference can invent intent or times and complicates testability.

### Fixed Asia/Shanghai time contract

TimePrism accepts local timestamps without offsets. Convert validated modern Shanghai wall time to epoch using explicit +08:00; do not use the Windows timezone. Display the IANA zone and offset in every review and notification. Shanghai has no modern DST. Other zones and DST ambiguity are unsupported and require restatement in Shanghai time. Existing Todo wall times are now interpreted in this documented zone.

### Durable write receipt with conservative uncertainty

Persist an atomic journal adjacent to the encrypted session. A UUID request ID, origin/user binding, reviewed fields and state are written before POST. Put an identifiable `meguri-request:<uuid>` marker in Todo detail. Store confirmed server records; retries re-read current server data, so cancelled/completed/edited cached records cannot re-enter delivery as active. Serialise creation calls so double confirms cannot race. Acquire Electron single-instance ownership for the profile after the existing Windows elevation handoff, before starting reminder/outbox writers; another desktop instance focuses the owner instead of starting duplicate polling. Definite 4xx rejections can retry; ambiguous 5xx/network/invalid responses cannot POST again. A read finding exactly one matching marker and matching schedule confirms recovery. An absent/altered marker leaves uncertainty, since absence is not proof that an in-flight or delayed write cannot commit. This sacrifices automatic retry after ambiguous failure to prevent duplicate creation without a server idempotency contract. Store corruption fails closed. Journal size is bounded, and unresolved entries are exposed to the panel after reload. Re-sending an identical unresolved title/time restores its request ID; the trusted client also rejects an uncertain duplicate using a different ID. Tokens never enter the journal or renderer.

Canceling before dispatch prevents the write. After dispatch, cancellation stops the renderer's current review flow only after the in-flight call returns; a confirmed response is still shown, while an uncertain result refreshes the pending request list and keeps the original journal entry available for later reconciliation. It never claims to recall a request already sent to TimePrism.

### Narrow lifecycle operations and local snapshot safety

Use existing full PUT shape for edits and cancellation (reminder disabled, Todo retained). Require selection and review in TodoPanel; no model decides a target. Serialise writes against connection changes, validate returned fields and reconcile a complete snapshot after mutation. Apply a confirmed record immediately so the old time/cancelled reminder cannot trigger during the next fetch. On synchronization error clear the snapshot; resume only after a current successful read. Persist dedupe before emission; store failure prevents emission rather than silently losing replay protection. This is at-most-once local issuance; OS/renderer process crash at the delivery boundary can still lose presentation, and notification APIs do not confirm that the human read it.

## Risks / Trade-offs

- [No server scheduling or declared idempotency] → Block offline/action requests and use conservative ambiguous-write reconciliation.
- [Production silently normalizes reminder fields] → Compare returned title/time/precision/offset/enabled fields; report unverified record and keep its server ID for management.
- [Remote edits between reads] → Bound freshness, stop after sync failure, refresh on focus and mutate local snapshots only with acknowledged records; partition cannot provide a timing guarantee.
- [Account changes during requests] → Serialise authenticated operations and pin each journal entry to account/origin; expose only that account's pending entries.
- [Draft lost on renderer reload] → Persist dispatched requests in journal and restore unresolved review cards; unsubmitted chat remains existing draft behavior.
- [User cancels while a write is in flight] → Stop the review flow after the current call returns, retain the request journal, and report a confirmed remote result if one arrives.
- [Delivery side effects] → Existing selected-owner QQ outbox remains explicit opt-in; Core receipt is shown only as receipt. No new external sends are introduced.

## Migration Plan

No data migration or committed-history rewrite. New request journal is additive under Electron user data; existing sessions, Todo records and preferences remain readable. Deploy the desktop build only after normal checks. Until live zero-minute writes and readback are verified, creation checks the returned contract every time and documentation marks production E2E unverified. Rolling back the desktop preserves server Todos. A future server scheduler must be independently implemented/deployed and verified before offline support is advertised.
