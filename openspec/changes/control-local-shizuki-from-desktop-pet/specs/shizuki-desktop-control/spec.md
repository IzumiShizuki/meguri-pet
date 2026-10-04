## Purpose

Let Meguri Pet discover, inspect, and safely operate a running local Shizuki desktop application through Shizuki's authenticated versioned loopback control protocol.

## ADDED Requirements

### Requirement: Core discovers the current Shizuki process securely
Meguri Core SHALL discover Shizuki from the configured per-user `control-api.json` manifest and SHALL treat a missing, stale, malformed, or incompatible manifest as an offline integration. The discovered endpoint MUST use HTTP on the literal IPv4 loopback host `127.0.0.1`, and the manifest token MUST remain inside the trusted Core process.

#### Scenario: Compatible Shizuki is running
- **WHEN** the manifest identifies a live process, a valid `127.0.0.1` endpoint, and protocol major version `1`
- **THEN** Core can perform the health and authenticated capability handshake

#### Scenario: Manifest is absent
- **WHEN** the configured manifest does not exist
- **THEN** Shizuki operations return a stable offline result that tells the user to start the Shizuki desktop application

#### Scenario: Manifest endpoint is not strict loopback
- **WHEN** the manifest endpoint uses a non-HTTP scheme, a host other than literal `127.0.0.1`, user information, a fragment, or an unexpected path
- **THEN** Core rejects the manifest without sending the bearer credential

#### Scenario: Protocol major is incompatible
- **WHEN** either the manifest or health response advertises a protocol major other than `1`
- **THEN** Core rejects the integration as incompatible and does not invoke commands

### Requirement: Core exposes bounded Shizuki capabilities
When enabled, Meguri SHALL register an authenticated state-read capability and explicit local-control capabilities for every Shizuki `1.0` navigation, window, and music action. All capabilities MUST require the server-authorized `shizuki:control` scope.

#### Scenario: Planner inspects available Shizuki tools
- **WHEN** an authorized AGENT Turn is frozen
- **THEN** the planner sees separate stable capabilities for reading state, opening each allowlisted destination, controlling the window, and controlling music

#### Scenario: Hosted or untrusted client lacks local scope
- **WHEN** `shizuki:control` is not present in server policy
- **THEN** no Shizuki capability is exposed regardless of request-body content

### Requirement: Commands follow advertised protocol capabilities
Before sending a command, Core SHALL validate protocol health and fetch the authenticated capability document. It SHALL invoke only the fixed command mapped to the selected Meguri capability and only when that command is advertised by the running Shizuki process.

#### Scenario: User opens Shizuki music
- **WHEN** the planner selects the explicit Shizuki music-navigation capability and the running application advertises `app.navigate`
- **THEN** Core sends a uniquely identified `app.navigate` command with destination `music` and returns the correlated result

#### Scenario: User pauses Shizuki playback
- **WHEN** the planner selects the explicit pause capability and the running application advertises `music.pause`
- **THEN** Core sends `music.pause` with an empty payload and reports successful execution

#### Scenario: Running version does not advertise a mapped command
- **WHEN** the fixed command is absent from the authenticated capability document
- **THEN** Core reports the operation as unsupported and does not post the command

#### Scenario: Authentication rotates during an operation
- **WHEN** a protected request is rejected as unauthorized because Shizuki restarted
- **THEN** Core reloads discovery and may retry only the request known not to have been dispatched, without exposing either token

### Requirement: Shizuki state and failures are model-safe
State and command results returned to the Agent SHALL contain only normalized application data and a concise execution summary. They MUST NOT contain the bearer token, authorization header, manifest contents, or unrestricted remote error bodies.

#### Scenario: Agent reads application state
- **WHEN** the state capability succeeds
- **THEN** the observation summarizes whether the renderer is ready plus the normalized route, window, and player state supplied by Shizuki

#### Scenario: Renderer is unavailable or times out
- **WHEN** Shizuki returns a structured `RENDERER_UNAVAILABLE` or `RENDERER_TIMEOUT` failure
- **THEN** Meguri returns a stable, bounded failure summary and does not guess that the requested action succeeded

### Requirement: Explicit Shizuki requests enter bounded tool execution
The server SHALL recognize unambiguous Shizuki-control wording as requiring an AGENT tool turn when local capability authority and the limited ReAct runtime are available.

#### Scenario: User addresses Shizuki by name
- **WHEN** the user asks Meguri to open, show, navigate, or control music in Shizuki
- **THEN** the execution-mode resolver selects the bounded AGENT path so an authorized Shizuki capability can be chosen

#### Scenario: Ordinary companionship message
- **WHEN** the user message does not indicate Shizuki or local application control
- **THEN** enabling the integration alone does not force that Turn into AGENT mode
