## Purpose

Connect consented native desktop conversations to verified authoritative memory and let the user inspect and manage recalled memories and pending candidates.

## ADDED Requirements

### Requirement: Verified and consented formal memory
The desktop SHALL request formal-memory capability only when the user enables it and accepts the data policy. The server MUST enforce verified tenant, user, client and session scope and its existing memory authorization. Missing authoritative storage or bridge configuration MUST be reported as unavailable rather than simulated with browser history or a fake provider.

#### Scenario: Authorized memory
- **WHEN** a consented desktop identity is permitted and authoritative storage is available
- **THEN** chat requests can recall formal memory and record reviewed-policy candidates.

#### Scenario: Missing provider or authorization
- **WHEN** the desktop has no authorized binding or its authoritative provider is unavailable
- **THEN** memory status identifies the missing dependency and chat continues without claiming memory was written.

### Requirement: Scoped memory management
The desktop SHALL list active memories, pending candidates and deleted memories for the verified current user, permit policy-compliant candidate approval or rejection, soft deletion, restoration and data export, and display failure or policy rejection accurately.

#### Scenario: Review a candidate
- **WHEN** the user approves a candidate belonging to the current identity
- **THEN** the existing review policy and audit rules determine its outcome and the UI refreshes the resulting status.

#### Scenario: Reject foreign scope
- **WHEN** a request attempts to manage another user's memory or supplies an actor identity
- **THEN** the server rejects it without a mutation.

### Requirement: Bounded summary controls
The user SHALL control automatic summaries and an interval of 3 to 30 successful turns and request a current-session summary manually. Summaries MUST be bounded, scoped, consent-gated and idempotent; disabling memory MUST stop further recall, candidate recording and summaries without deleting existing data.

#### Scenario: Automatic summary
- **WHEN** the configured successful-turn interval is reached with memory consent enabled
- **THEN** at most one bounded summary request for that conversation checkpoint is submitted and any resulting candidates appear for review.

#### Scenario: Withdraw memory use
- **WHEN** memory is disabled
- **THEN** subsequent chat requests and summary actions do not request formal-memory use.
