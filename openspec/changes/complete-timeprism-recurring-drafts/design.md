## Context

See proposal.md. Desktop/site already share six-field Spring cron, Sunday=0, rule-local timestamps and MINUTE/DAY advances. AstrBot has an atomic v1 draft store and a separate owner-only privileged Core writer with reconciliation markers. Site HEAD ec29b37c is clean and existing GET materializes instances.

## Goals / Non-Goals

Goals: create weekly/daily rules through an explicit owner-private draft; retain all schedule fields across confirmation/retry; expose pure snapshots separately from explicit synchronization. Non-goals: arbitrary cron NLP, monthly/interval parsing, editing real tasks, changing existing one-off timezone semantics, cross-device exactly-once delivery or new credentials.

## Decisions

- Add `/v1/reminders/timeprism/recurring-rules` and a distinct recurring DTO. Reuse existing writer's HTTP/auth/owner/journal rather than a second credential path. Extend journal entries additively; old entries deserialize with null recurring data. Kind-prefixed fingerprints prevent request IDs crossing operation types.
- Parser accepts explicit weekdays (including Mon–Fri), daily cadence, one clock, optional IANA zone, advance and ISO date bounds. Unsupported/ambiguous schedules return clarification and never create a write. Default zone stays Asia/Shanghai; new draft start date is captured once in that zone and survives retries.
- Retain draft v1; optional kind/schedule fields distinguish recurring drafts while old drafts retain the one-off path. Editing resets the request ID; uncertain/submitting drafts cannot be edited/cancelled. Only explicit confirm invokes the privileged client. Confirmed receipts verify every schedule field.
- Server snapshot is additive and read-only; explicit sync owns legacy materialization. Old list GET keeps behavior for compatibility. New clients do not silently fall back to a mutating GET for pure reads; explicit background sync may use a documented old-server fallback.
- Common JSON fixtures cover weekday/Cron/zone/advance/start/end across Python, Java and desktop. Site tests mock database/event dependencies, no live credentials or migration.

## Risks / Trade-offs

- DST gap/ambiguous expression → reject invalid wall-clock boundaries, display rule timezone; cron runtime remains the site's authority.
- Lost write response → preserve uncertain journal and reconcile by exact request marker; no blind second POST. Expired schedule cannot prevent reconciliation of an already-submitted request.
- Old site lacks snapshot → return an actionable compatibility error for pure read, never claim no side effects while using old GET.
- Site changes outside main workspace require reviewed filesystem access and a separate scoped commit. Deployment is not part of this change.
