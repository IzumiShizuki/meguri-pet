## Purpose

Make local durable runtime selection explicit and verifiable so completed conversation events, session context and background jobs can be recovered without duplicating model or tool work after a restart.

## ADDED Requirements

### Requirement: Explicit PostgreSQL runtime activation
The local launcher SHALL support a separately configured PostgreSQL runtime using external secret files. Durable mode MUST fail closed when its configuration or database is unavailable and MUST NOT silently substitute an in-memory runtime or reuse memory credentials without explicit configuration.

#### Scenario: Configured durable launch
- **WHEN** the runtime-specific PostgreSQL configuration is supplied
- **THEN** Turn/events, session context and background job stores select the durable implementations

#### Scenario: Invalid durable configuration
- **WHEN** durable mode is selected but required configuration is missing or invalid
- **THEN** startup fails with a content-free diagnostic

### Requirement: Restart recovery invariants
Completed Turn events and session context SHALL be recoverable with unchanged sequence and idempotency identity. Interrupted generation MUST receive a clear failure terminal while preserving persisted text and MUST NOT call the model again on the original Turn. Background retries MUST retain stable operation identity.

#### Scenario: Completed replay after reconstruction
- **WHEN** persistent runtime components are reconstructed
- **THEN** completed snapshots, events, context and the original idempotent Turn identity remain readable

#### Scenario: Interrupted generation
- **WHEN** a generating Turn loses its execution owner and is recovered
- **THEN** a failure terminal explains the interruption, prior text remains and no original model stream is restarted

#### Scenario: Job recovery
- **WHEN** a memory job loses its lease before acknowledgement and is retried by a new worker
- **THEN** the same logical operation is used without changing the completed Turn

#### Scenario: Full Core process restart
- **WHEN** a separately launched Core process exits and a new Core process starts with the same isolated durable storage
- **THEN** authenticated HTTP and SSE expose the same completed Turn/events and pending feedback, restored session context is available to the new Core, and an expired background job lease is recovered under its original identity

### Requirement: Evidence identifies its environment
Recovery reports SHALL distinguish mocked/in-process tests, isolated database reconstruction or process restart, and live deployment evidence. Missing real PostgreSQL evidence MUST remain explicitly unverified rather than marked production-ready.

#### Scenario: Offline checks only
- **WHEN** verification runs without a real PostgreSQL instance
- **THEN** the report identifies those checks as offline and does not claim real-database or production recovery acceptance
