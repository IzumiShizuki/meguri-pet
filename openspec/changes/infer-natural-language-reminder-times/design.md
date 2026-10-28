## Context

See proposal.md for motivation. The shared parser currently requires an explicit date and rejects an unqualified 1–12 hour before `ReminderFlow` can open its editable review state. Shanghai wall-time conversion and the explicit confirmation/write path already exist.

## Goals / Non-Goals

**Goals:** Produce a future reminder from a clear request with deterministic Asia/Shanghai defaults, raise the Todo surface immediately, and avoid an unnecessary confirmation click when a connected account can safely accept the write.

**Non-Goals:** Change reminder persistence, add LLM-based date inference, change explicit date semantics, support recurring reminders, or add AstrBot Todo writes.

## Decisions

- When the request omits a date, use the current Asia/Shanghai calendar date as the first candidate. If its time has passed, advance to the next occurrence rather than asking for a date.
- For a bare hour from 1 through 12, evaluate both AM and PM candidates and choose the earliest candidate strictly after the parse instant. For an hour with an explicit day part or an unambiguous 24-hour value, use that one clock time and advance to its next occurrence only when the date was omitted.
- When the user explicitly supplies a date or relative day, keep that calendar date fixed. If every valid interpretation is past, retain the existing clarification path rather than silently changing the requested date.
- Feed inferred values through the existing `ready` result. ChatDock raises the Todo surface and confirms only when the account is connected and the request is not already dispatched; failures retain the existing review/retry state rather than losing the draft.
- When no clock is supplied, use 12:00 for sleep-related titles and otherwise the next local half-hour. The resulting timestamp is visible in the saved Todo and can still be edited through the Todo surface.
- Keep inference deterministic and independent of an LLM. Candidate comparison uses validated Shanghai wall times converted to instants, including across midnight and year boundaries.

## Risks / Trade-offs

- [The nearest future AM/PM interpretation can differ from what the user intended] → Show the resolved date and time in the editable review and require explicit confirmation.
- [Moving a date-omitted time to tomorrow can surprise users when both occurrences today have passed] → Display the complete inferred timestamp before confirmation; explicit dates never roll forward.

## Migration Plan

No stored Todo or API migration is needed. Existing reminders and writes retain their current contract. Rollback consists of reverting the parser and this change's specification.
