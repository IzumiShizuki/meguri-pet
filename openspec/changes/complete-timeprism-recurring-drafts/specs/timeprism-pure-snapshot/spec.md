## Purpose

Allow desktop and site integrations to inspect existing Todo data without generating instances or dispatching reminders, while retaining the existing legacy endpoint and an explicit synchronization operation for scheduled work.

## ADDED Requirements

### Requirement: Pure snapshot and explicit synchronization
The site SHALL provide an authenticated snapshot query with zero instance writes and zero reminder events; an explicit sync operation performs materialization idempotently. Legacy GET behavior SHALL remain compatible.

#### Scenario: Repeated query
- **WHEN** a client repeatedly requests snapshots
- **THEN** no database mutation, instance materialization or reminder dispatch occurs.

#### Scenario: Explicit synchronization
- **WHEN** the same account requests synchronization twice for the same dates
- **THEN** rule instances and date completion states remain consistent and no duplicate instance is created.

#### Scenario: Old server
- **WHEN** a new pure-read client reaches a server without the snapshot endpoint
- **THEN** it reports unsupported compatibility without silently invoking the legacy mutating GET.
