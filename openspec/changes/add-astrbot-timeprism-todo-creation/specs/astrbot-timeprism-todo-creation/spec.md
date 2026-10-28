## Purpose

Lets the configured owner create one-time TimePrism reminders from AstrBot private chat, review and edit the proposed details, then explicitly confirm creation.

## ADDED Requirements

### Requirement: Only an authorized private sender can prepare a TimePrism reminder
The system SHALL accept reminder creation requests only from an explicitly configured owner sender in a private chat. Group messages and other senders SHALL NOT create, edit, confirm, or cancel drafts.

#### Scenario: Authorized owner asks for a reminder
- **WHEN** a configured owner sends a supported reminder request in a private chat
- **THEN** the system prepares a draft for review without creating a TimePrism Todo

#### Scenario: An unconfigured sender or group asks for a reminder
- **WHEN** a sender outside the owner allowlist or any group participant submits a reminder request or draft command
- **THEN** the system creates no draft and performs no TimePrism write

### Requirement: Resolve supported reminder times using Asia/Shanghai defaults
The system SHALL interpret supported one-time reminder requests in Asia/Shanghai. When the date is omitted, it SHALL choose the nearest future date and time matching the request; a bare 1–12 hour SHALL select the earliest future AM/PM occurrence without asking which period. When the date is explicit and its requested time has passed, the system SHALL ask for a future time instead of silently moving the date.

#### Scenario: Bare hour has a single nearest future interpretation
- **WHEN** the owner requests a reminder for “1点” at 00:00 Asia/Shanghai and omits the date and period
- **THEN** the draft uses 01:00 on the current Shanghai calendar day without asking which day or whether the time is AM or PM

#### Scenario: Omitted date rolls forward to the nearest future occurrence
- **WHEN** the owner requests a bare hour that has already passed today and omits the date
- **THEN** the draft uses the earliest future matching occurrence, including the next day when necessary

#### Scenario: Explicit past date is not silently changed
- **WHEN** the owner supplies an explicit date whose requested time is not in the future
- **THEN** the system asks the owner to revise the date or time and creates no draft ready for confirmation

### Requirement: Previewed drafts require explicit owner confirmation
The system SHALL show the reminder title and resolved local date and time before writing. The authorized owner SHALL be able to edit or cancel the draft, and no TimePrism write SHALL occur until that owner explicitly confirms the current draft.

#### Scenario: Preview without confirmation
- **WHEN** the system parses or displays a reminder draft
- **THEN** it shows the title, date, time, and Asia/Shanghai timezone and makes no external write

#### Scenario: Owner edits a draft before confirming
- **WHEN** the same authorized owner edits an active draft with a supported reminder request
- **THEN** the system replaces the draft details and presents the revised preview without writing to TimePrism

#### Scenario: Owner confirms the current draft
- **WHEN** the same authorized owner explicitly confirms an active draft
- **THEN** the system submits exactly the previewed title and time for creation and reports the confirmed result or a recoverable failure

#### Scenario: Owner cancels a draft
- **WHEN** the same authorized owner cancels an active draft
- **THEN** the draft is marked cancelled and cannot be submitted for creation

### Requirement: Core writes reminders only to the configured TimePrism owner account
The system SHALL keep Shizuki login credentials outside AstrBot and outside the request body. Core SHALL use only its configured owner credentials to create a one-time TimePrism Todo with an enabled minute-precision deadline reminder. If Core credentials or owner binding are unavailable, it SHALL fail closed without creating a Todo.

#### Scenario: Confirmed write creates a reminder on the configured owner account
- **WHEN** Core receives an authenticated, valid confirmation request with a future Asia/Shanghai time and the configured owner account is available
- **THEN** Core creates one unfinished TimePrism Todo with the requested title, due time, and enabled reminder, and returns the verified Todo identity

#### Scenario: Writer is not configured
- **WHEN** a confirmed draft reaches Core without complete external credentials or a valid configured owner binding
- **THEN** Core reports that writing is unavailable and creates no Todo

### Requirement: Confirmed writes are idempotent and recoverable
The system SHALL associate every confirmed draft with a stable request identifier. Repeating the same request identifier and payload SHALL NOT create a duplicate Todo; reusing that identifier with different content SHALL be rejected. When the remote result is uncertain, Core SHALL reconcile the request before any further create attempt.

#### Scenario: Same confirmation is retried
- **WHEN** Core receives the same request identifier and payload again after a successful or uncertain submission
- **THEN** it returns the existing verified result or an uncertainty status and does not create a second Todo

#### Scenario: Request identifier is reused with changed content
- **WHEN** Core receives an existing request identifier with a different title or due time
- **THEN** it rejects the conflicting request and performs no new create
