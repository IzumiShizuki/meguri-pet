## Purpose
Provide one account-scoped desktop view of authoritative site tasks, agreements and notes with explicit synchronization, conflict handling and offline draft identity.

## ADDED Requirements
### Requirement: Shared tasks and notes
The desktop SHALL use the site's stable task/note IDs and SHALL preserve null deadlines for undated agreements. Notes SHALL have independent editing and no task reminder or completion semantics.
#### Scenario: Undated agreement
- **WHEN** a user saves an agreement without a deadline
- **THEN** the body preserves null due/reminder fields and the display never labels it overdue
#### Scenario: Independent note
- **WHEN** a user edits a note
- **THEN** only the selected note is changed and its content is displayed as text

### Requirement: Protected synchronization
The desktop SHALL require advertised protected-write capabilities, immutable request IDs and expected versions. It SHALL retain drafts on conflict and SHALL NOT overwrite a newer version automatically.
#### Scenario: Concurrent edit
- **WHEN** a save receives HTTP 409
- **THEN** the local draft is retained, the conflict is visible and no automatic retry changes its expected version
#### Scenario: Unknown result and restart
- **WHEN** the network fails after dispatch and the application restarts
- **THEN** explicit retry uses the same account, request ID and body and success requires a valid server receipt

### Requirement: Account-scoped offline view
The desktop SHALL separate caches and pending mutations by origin and user identity, mark offline snapshots and never present queued edits as synchronized.
#### Scenario: Account changes
- **WHEN** a different account becomes current
- **THEN** the previous account's cache and pending writes cannot be read or dispatched for the new account
#### Scenario: Capability unavailable
- **WHEN** the server does not advertise the new center contract
- **THEN** dependent writes remain disabled without falling back to legacy mutating reads

### Requirement: Predictable task ordering
The desktop SHALL order unfinished tasks by HIGH/MEDIUM/LOW priority, deadline with null last, sort order and stable ID, while evaluating recurring deadlines in their rule timezone.
#### Scenario: Equal priorities and empty deadline
- **WHEN** two tasks have equal priority and one has no deadline
- **THEN** the dated task is ordered first without inventing a deadline for the other

### Requirement: Bounded MCP error observations preserve local execution authority
For MCP tools/call results with isError=true, the Core SHALL preserve only locally allowlisted structured error metadata as untrusted external data. A detail-bearing response SHALL require structuredContent.ok=false and an exact string match between a valid UUID in the current input.request_id and structuredContent.request_id. Missing, mismatched, malformed or oversized details SHALL fall back to the generic MCP failure result.

The allowlisted detail fields SHALL be code, optional http_status, retryable, remote_uncertain (copied from the remote uncertain field), and request_id. Code values SHALL come from a fixed application-owned allowlist; status SHALL be an integer from 400 through 599; retryable and uncertain SHALL be JSON booleans. The whole structuredContent object SHALL be no larger than 4,096 UTF-8 bytes. The Core SHALL NOT propagate remote message, source, content text, HTML or arbitrary properties into the failure observation. It SHALL NOT parse content text to recover missing structured details.

Remote metadata SHALL NOT decide approval, identity, retry eligibility or whether a dispatched write completed. A dispatched MCP WRITE returning isError SHALL remain UNKNOWN_OUTCOME with VERIFY_BEFORE_RETRY and local retryable=false, including when remote_uncertain=false. Neither reads nor writes SHALL automatically retry an isError result. Existing approval/request binding and operation replay checks SHALL remain in force.

#### Scenario: Conflict details do not prove a write failed
- **WHEN** an approved MCP write receives a matching bounded CONFLICT/409 reply with uncertain=false
- **THEN** the result contains untrusted conflict details, remains UNKNOWN_OUTCOME with VERIFY_BEFORE_RETRY, and cannot be automatically retried
- **AND** executing the same tracked operation again returns its stored local result without sending another tools/call request

#### Scenario: Remote retry advice does not authorize a retry
- **WHEN** an approved read or write receives a matching UPSTREAM_ERROR/503 with retryable=true
- **THEN** safe remote claims are visible, the local result remains non-retryable, and the executor makes one HTTP invocation

#### Scenario: Unbound or invalid details
- **WHEN** either request UUID is missing or invalid, the UUID strings differ, the code is unknown, a selected field has the wrong JSON type, or structuredContent exceeds the size bound
- **THEN** the result contains no remote details and retains generic local error/status handling

#### Scenario: Existing read tools without request UUID
- **WHEN** a read tool's declared input schema has no request_id
- **THEN** its error remains generic; the Core does not invent an ID, bypass schema validation, or bind an error to an unrelated operation ID

#### Scenario: Approval remains necessary
- **WHEN** the operation is unapproved, declined, or attempts to reuse approval with different input
- **THEN** the existing local approval rejection occurs before HTTP dispatch and no remote detail is exposed
