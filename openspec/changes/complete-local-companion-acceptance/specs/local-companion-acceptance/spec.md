## Purpose
Provide auditable companion behavior across persona prompts, controlled capability execution and the current site Todo contract, without contaminating real user memory or bypassing extension approval.

## ADDED Requirements
### Requirement: Preserve persona style and evidence purpose
The system SHALL include configured speech style in trusted persona instructions and identify retrieved canonical dialogue as style evidence while retaining its untrusted data boundary.
#### Scenario: Canonical dialogue and configured style
- **WHEN** a turn resolves a persona and retrieves canonical dialogue
- **THEN** the model request includes the speech style and explicit style-only purpose without promoting dialogue instructions to policy
#### Scenario: Isolated memory consent
- **WHEN** a fictional identity enables memory recall in an isolated test backend
- **THEN** only that identity's approved memories enter a new session and disabled consent yields no memory retrieval

### Requirement: Preserve site recurring Todo semantics
The integration SHALL preserve site recurrence metadata, weekday selection, advance reminders and per-date completion using existing authenticated APIs; reminders SHALL deduplicate each occurrence.
#### Scenario: Weekday recurring task
- **WHEN** the site provides a weekly rule and generated dated tasks
- **THEN** the client preserves selected weekdays and instance dates, computes reminders with the declared time zone and updates only the selected occurrence

### Requirement: Evidence based capability acceptance
Capability acceptance SHALL distinguish discovery, configuration, selection, approval, execution, receipt and recovery; unfamiliar extensions SHALL require explicit review before installation or activation.
#### Scenario: Controlled capability execution
- **WHEN** a permitted fixture skill or MCP tool is selected and executed
- **THEN** its actual result and receipt are verified, denied actions produce no side effect, and a later permitted request can recover
#### Scenario: Unfamiliar community extension
- **WHEN** a community listing describes an extension
- **THEN** listing content remains untrusted and no unknown code or model is installed or run without separate authorization

### Requirement: Verified local runtime deployment
Local acceptance SHALL record actual runtime identity, preserve user data, and prepare rollback before replacing a verified project service.
#### Scenario: Deployment confirmation
- **WHEN** a verified build replaces the local Core after required host confirmation
- **THEN** the new process, health and build evidence are checked before live acceptance; a failed replacement attempts the recorded safe rollback

### Requirement: Concurrent idempotent requests remain responsive
Duplicate requests SHALL return the same turn identity while that turn is executing, without deadlocking status or cancellation access.
#### Scenario: Replay races with persistence
- **WHEN** a duplicate idempotency request overlaps execution persistence for the same turn
- **THEN** both operations finish within their bounds and expose the same durable turn without lock inversion

### Requirement: Cancellation respects execution ownership
A cancellation request SHALL persist cancellation intent without writing execution terminal events outside the owning lease. The execution chain SHALL emit one cancelled terminal event, dispose unfinished provider work and allow the next turn to complete with its own response.
#### Scenario: Cancel before execution claim
- **WHEN** cancellation arrives before a new or queued turn has claimed its execution lease
- **THEN** the request succeeds without a terminal write from the request path, and the scheduled execution claims ownership before persisting cancellation
#### Scenario: Repeated cancellation during provider work
- **WHEN** the same active turn is cancelled repeatedly while its provider has not completed
- **THEN** one cancellation terminal event is persisted, late provider output is ignored and a following turn can complete

### Requirement: Frozen network authority
Limited ReAct execution SHALL inherit network authority only from its server-created frozen capability exposure. It SHALL NOT grant authority from model arguments, supplied capability identifiers or public metadata. Explicit user instructions prohibiting network access SHALL exclude network tools and produce no external call.

#### Scenario: Public discovery uses existing authorized scope
- **WHEN** the server freezes an authorized public metadata capability with network access
- **THEN** its ReAct invocation retains that frozen permission, executes the bounded read, and exposes an untrusted result without granting installation or administration rights

#### Scenario: Explicit offline request
- **WHEN** the user forbids network access for an otherwise eligible AGENT turn
- **THEN** public discovery remains unavailable and its HTTP fixture receives zero calls

### Requirement: Ordinary public Skill entry
A direct ordinary-chat request to search or inspect public ModelScope Skill metadata SHALL be eligible for the existing read-only agent path when the server has granted skill:read. Intent detection SHALL NOT create capability authority or imply installation/execution approval.

#### Scenario: Direct public metadata request without execution mode
- **WHEN** an authorized user explicitly asks to discover or inspect public Skill metadata without setting execution_mode
- **THEN** the server can select the bounded tool path and return its observed public result

#### Scenario: Non-action mentions remain non-action
- **WHEN** the text is quoted, explanatory, asks how to implement the feature, explicitly forbids network access, or lacks authorized skill:read
- **THEN** it does not trigger public metadata HTTP calls merely from those keywords

### Requirement: Deadline terminal persistence retains execution ownership
A turn that exceeds its deadline SHALL persist exactly one deadline terminal event while holding its execution lease. Deadline cancellation SHALL NOT release ownership before terminal persistence, and a queued turn SHALL claim ownership before recording expiration without invoking its provider.

#### Scenario: Active provider exceeds the deadline
- **WHEN** an active provider never finishes before the turn deadline
- **THEN** the provider is cancelled, one TURN_DEADLINE_EXCEEDED terminal event is persisted before lease release, late output is ignored and a subsequent turn can complete

#### Scenario: Queued turn expires behind its predecessor
- **WHEN** a queued turn reaches its deadline while an earlier turn is still executing
- **THEN** the queued turn claims its own lease and records one deadline terminal event without provider execution, while later turns continue to wait for the earlier turn

### Requirement: Session event polling stays scoped
Session event polling SHALL inspect lightweight lifecycle state only for the requested session and identity, without reloading unrelated turn payloads or querying one owner per event. It SHALL observe inactivity before reading events and verify all visible terminal events before completing the stream.

#### Scenario: Shared session identifier across identities
- **WHEN** different users or clients have turns sharing a session identifier
- **THEN** a scoped event stream emits only that identity's events and unrelated active turns do not keep it open

#### Scenario: Turn arrives while terminal events are being read
- **WHEN** another visible turn arrives after the polling state snapshot
- **THEN** completion rechecks session state and drains the new turn's terminal event before closing

### Requirement: Lifecycle persistence is atomic between execution paths
Lifecycle transition, durable event append and rollback SHALL be serialized for each turn. A competing cancellation path SHALL NOT signal completion from an uncommitted in-memory terminal state, release the execution lease prematurely, or roll back another path's durable terminal event. Unhandled execution errors SHALL report only the turn identifier and exception category without exception messages, stack traces or request contents.

#### Scenario: Provider cancellation races the cancellation observer
- **WHEN** the provider error path is persisting cancellation while the polling observer also sees cancellation intent
- **THEN** the observer waits for the same turn's terminal persistence, exactly one cancellation event commits before lease release, and a following turn completes normally
