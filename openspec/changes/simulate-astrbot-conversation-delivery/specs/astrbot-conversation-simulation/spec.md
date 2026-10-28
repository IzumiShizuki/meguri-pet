## Purpose

Provide repeatable AstrBot private and group conversation simulations that exercise adapter transport and captured delivery without requiring a QQ account or sending messages to real platform users.

## ADDED Requirements

### Requirement: Simulation isolates users and delivery
The simulator SHALL use synthetic identities, disposable state and system-assigned loopback ports. Outputs SHALL be captured locally and SHALL NOT be sent to a real messaging platform.

#### Scenario: Private and group outputs remain distinct
- **WHEN** the same synthetic sender chats privately and in two groups
- **THEN** session identities and captured destinations are distinct, and text is delivered to the matching destination

#### Scenario: Resources are released
- **WHEN** a simulation completes or fails
- **THEN** its listener, HTTP clients and temporary state are closed without changing live platform configuration

### Requirement: Simulation exercises real adapter transport
The simulator SHALL execute the plugin entrypoint, gateway and HTTP/SSE client with actual loopback HTTP transport. Controlled Core responses SHALL be identified as fixtures; an optional configured-Core run SHALL record actual responses separately.

#### Scenario: Successful private and group requests
- **WHEN** eligible private and group events are submitted
- **THEN** hello, turn creation and event delivery are observed and the completed output reaches the local collector

#### Scenario: Restricted or duplicate events
- **WHEN** group routing is disabled, another plugin owns a command, or the same platform message is redelivered
- **THEN** no unintended Core turn or duplicate captured reply is created

#### Scenario: Retrieval state and controlled failures
- **WHEN** retrieval reports unavailable/disabled outcomes, Core returns an error, or the collector rejects a delivery
- **THEN** the report retains the observed outcome and does not label the rejected delivery as successful

### Requirement: Simulation checks actions and evidence separately
The simulator SHALL verify reminder draft, confirm and cancel effects using observed requests and receipts. Generation completion alone MUST NOT be treated as task creation, cancellation, current-fact verification or platform delivery.

#### Scenario: Reminder review and confirmation
- **WHEN** an authorized private sender prepares a reminder and explicitly confirms it
- **THEN** preparation does not write, confirmation uses the expected receipt and repeated confirmation does not create another task
- **AND** a supported relative-time numeral such as “十分钟后” resolves to a valid draft

#### Scenario: Group reminder and cancelled draft
- **WHEN** a group sender attempts a reminder or an authorized sender cancels a pending draft
- **THEN** no unauthorized task write occurs

#### Scenario: Configured Core returns an unsupported action claim
- **WHEN** a configured-Core response claims an action without an observed action receipt
- **THEN** the output is retained as a finding rather than counted as action success
