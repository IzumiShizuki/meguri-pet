## Purpose

Enable the configured owner to create recurring TimePrism reminder rules through private-chat drafts with explicit confirmation, preserving the same schedule and recovery semantics used by the desktop and website.

## ADDED Requirements

### Requirement: Confirmed owner recurring drafts
The system SHALL preview weekly/daily cadence, weekdays, local time, IANA timezone, date bounds and reminder advance before any write; only the allowlisted owner in private chat can confirm.

#### Scenario: Multi-weekday confirmation
- **WHEN** the owner requests Monday/Wednesday/Friday at 18:30 with a ten-minute advance and explicit dates
- **THEN** the draft displays those fields and no site call occurs until confirmation; the confirmed rule matches all fields.

#### Scenario: Group or another actor
- **WHEN** a group or different actor attempts creation or confirms another actor's draft
- **THEN** no write occurs.

### Requirement: Compatible and recoverable writes
The system SHALL retain legacy one-off drafts and interfaces, use a stable request identity for retries, verify returned fields, and reconcile uncertain writes without duplicate POSTs.

#### Scenario: Response lost after commit
- **WHEN** a confirmed request loses the site response and the same draft is retried
- **THEN** exact-marker readback confirms the original rule without creating another rule, even after its schedule window ends.

#### Scenario: Invalid timing
- **WHEN** weekdays, timezone, wall time or boundaries are invalid or ambiguous
- **THEN** no remote write occurs and the caller receives a validation/clarification result.
