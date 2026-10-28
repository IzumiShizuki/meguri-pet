## Why

The native desktop memory API exists but has no configured authoritative database. The owner has authorized using the PostgreSQL server already configured for their projects.

## What Changes

- Provision a dedicated Meguri database and application role on the existing PostgreSQL server, with pgvector and the current memory migrations.
- Keep credentials and bridge tokens outside the repository and load them automatically during the existing Windows startup flow.
- Enable the native memory bridge with explicit text retrieval settings, without adding model configuration.
- Report actual database and bridge readiness, including any server prerequisite that cannot be completed.

## Capabilities

### New Capabilities

- `server-postgres-memory-runtime`: Isolated server PostgreSQL provisioning, external secret loading, and durable desktop memory startup.

### Modified Capabilities

None.

## Impact

Existing PostgreSQL server, memory migration job, Windows Core and memory bridge launchers, runtime configuration, and deployment documentation. Existing project data is preserved.
