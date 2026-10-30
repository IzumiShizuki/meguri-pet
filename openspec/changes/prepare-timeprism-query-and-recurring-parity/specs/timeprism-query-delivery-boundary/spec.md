## Purpose

Separate Todo snapshot reads from reminder delivery so that displaying or inspecting a list cannot itself emit notifications, while preserving explicitly enabled scheduled delivery and authoritative recurring-rule state.

## ADDED Requirements

### Requirement: Snapshot ingestion does not dispatch reminders
The desktop SHALL ingest a Todo snapshot without invoking notification delivery or persisting delivery deduplication. Explicit scheduled scans and acknowledged mutation processing SHALL remain available.

#### Scenario: Inspect a due Todo
- **WHEN** a list response contains a Todo whose reminder is due
- **THEN** snapshot ingestion emits zero reminders and writes no delivery receipt, and a later explicit scan may deliver it once

#### Scenario: Older read completes late
- **WHEN** an older background read completes after a newer accepted snapshot
- **THEN** it SHALL NOT replace the newer snapshot used by subsequent scans

### Requirement: Paused recurring rules suppress delivery
The desktop SHALL suppress reminders for instances whose attached authoritative recurring rule is disabled; unavailable rule metadata SHALL NOT cause an assumed timezone or notification.

#### Scenario: Paused rule with existing instance
- **WHEN** an incomplete, otherwise due instance belongs to an explicitly paused rule
- **THEN** no reminder event is emitted

### Requirement: Query side effects are represented honestly
The client SHALL NOT describe legacy server list requests that materialize instances as database-pure queries.

#### Scenario: Legacy site contract
- **WHEN** the server provides only its existing materializing list endpoint
- **THEN** diagnostics use offline fixtures or describe the request as synchronization, without silently issuing it as a read-only probe
