## Purpose

Activate the existing private-chat Todo workflow for the user's explicitly selected account, preserving precise identity binding, secret isolation, verified receipts and recoverable writes without sending platform test messages.

## ADDED Requirements

### Requirement: Activation binds the verified owner
Activation SHALL use the selected account's authenticated user ID, matching Core and adapter owner settings and one exact authorized private sender. Credentials SHALL remain in external restricted files, never public reports or repository sources.

#### Scenario: Owner login succeeds
- **WHEN** the selected credentials authenticate and the real private sender is identified
- **THEN** owner configuration can be activated with preserved rollback assets and no change to unrelated accounts or groups

#### Scenario: Account mismatch
- **WHEN** the remote login returns a different user ID or caller identity does not match
- **THEN** no Todo is written

#### Scenario: Private writer activation preserves existing chat routes
- **WHEN** legacy loopback conversations use their existing authentication policy
- **THEN** Todo writing SHALL still require its configured shared token and exact owner/client/session identity, without changing unrelated conversation or callback authentication behavior

### Requirement: Writes are bounded and reconcile safely
Todo HTTP exchanges SHALL bound complete response consumption including bodies that stall after headers, preserve response size limits, and retain uncertain writes for reconciliation using the same request ID.

#### Scenario: Confirmed repeated request
- **WHEN** a confirmed draft is submitted repeatedly with identical content
- **THEN** it returns the verified receipt without creating another Todo

#### Scenario: Minute precision receipt
- **WHEN** the website returns a verified Todo time including zero seconds
- **THEN** new, cached, reloaded and reconciled confirmed receipts SHALL return the draft's canonical minute precision, preserving strict adapter validation and the verified journal data

#### Scenario: Stalled response body
- **WHEN** login or a Todo response sends headers but does not complete its body
- **THEN** the call finishes within its configured bound, a possibly committed write remains uncertain, and later calls can recover

#### Scenario: Uncertain write retry
- **WHEN** the remote service may have committed the Todo before the response failed
- **THEN** retry reconciles the original marker and never blindly creates another copy

### Requirement: Acceptance leaves no platform messages or test tasks
Acceptance SHALL use local collectors instead of QQ sends, and SHALL identify and remove only its own synthetic account test task after receipt and idempotency checks.

#### Scenario: Live write acceptance
- **WHEN** an isolated synthetic event confirms a test draft for the owner
- **THEN** one verified Todo is observed, repeat confirmation does not duplicate it, cleanup is verified, and no QQ message is sent
