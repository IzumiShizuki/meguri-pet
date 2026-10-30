## Purpose

Preserve one authoritative site recurring rule across desktop edits, including selected weekdays, timezone, advance reminders, date bounds and independent completion of each dated instance without duplicate creation.

## ADDED Requirements

### Requirement: Explicit recurring date bounds are preserved
Recurring create and edit SHALL accept optional start and end wall-clock timestamps in the rule timezone, preserve omitted existing bounds, and verify acknowledged server values. Invalid dates, nonexistent local times and reversed bounds SHALL be rejected before a write.

#### Scenario: Date limited rule
- **WHEN** a user provides a start and end timestamp with weekday selections and advance reminders
- **THEN** the same normalized timestamps and schedule are submitted and checked on readback

#### Scenario: Existing schedule edit
- **WHEN** an edit omits bounds or other unrelated existing rule fields
- **THEN** existing values remain unchanged, while an explicit empty end removes the end bound

### Requirement: Repeated creates preserve request identity
Existing request identity and content binding SHALL remain effective across retries, midnight and UI reopening.

#### Scenario: Committed write loses response
- **WHEN** an identical create is retried after its response is lost
- **THEN** the client reconciles the original request without another create; different content under the same identity is rejected

### Requirement: Dated completion and schedule meaning remain independent
Completing one occurrence SHALL NOT complete a different date or alter the recurring rule. Weekday shortcuts and advanced expressions SHALL preserve their original schedule meaning.

#### Scenario: Complete a selected day
- **WHEN** one materialized occurrence is completed
- **THEN** the other occurrences and the recurring rule remain unchanged
