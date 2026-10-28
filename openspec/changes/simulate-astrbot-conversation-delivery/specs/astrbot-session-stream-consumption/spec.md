## Purpose

Keep successive AstrBot messages usable when the Core publishes monotonically sequenced events for an entire session, including earlier turns, while preserving the session boundary and current reply identity.

## ADDED Requirements

### Requirement: Consume session history without applying other turns
The AstrBot event consumer SHALL advance its session cursor over valid earlier-turn events in the requested session. It SHALL apply semantic/runtime/reply data and terminal status only for the requested turn. A different session MUST remain a protocol error.

#### Scenario: Second message replays the first turn
- **WHEN** a session event stream includes the completed first turn before the second turn
- **THEN** the second request completes with its own output and the earlier terminal event does not stop consumption

#### Scenario: Reconnect follows the shared sequence
- **WHEN** that stream ends and reconnects after an accepted sequence
- **THEN** consumption resumes after that sequence without duplicating a captured reply

#### Scenario: Event belongs to another session
- **WHEN** a response contains an event whose session differs from the requested session
- **THEN** the consumer rejects it without applying its text or semantic state
