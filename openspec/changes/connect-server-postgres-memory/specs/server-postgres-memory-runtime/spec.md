## Purpose

Connect the native desktop's authoritative memory to the owner's existing PostgreSQL server using isolated storage and durable external secret configuration.

## ADDED Requirements

### Requirement: Isolated server storage
Meguri memory SHALL use a dedicated database and application role on the existing PostgreSQL server, preserving existing project data and requiring the current schema and pgvector extension before reporting ready.

#### Scenario: Existing server database
- **WHEN** the server already contains another project's database
- **THEN** Meguri provisioning leaves that database and its permissions unchanged and configures dedicated memory storage

#### Scenario: Missing extension package
- **WHEN** pgvector cannot be installed using available authenticated server access
- **THEN** deployment reports the prerequisite and SHALL NOT report authoritative memory as ready

### Requirement: External durable configuration
The Windows launchers SHALL load configured secret files outside the repository, honor explicit environment overrides, and never log credential contents.

#### Scenario: Restart with prepared files
- **WHEN** the standard secret files exist and the user starts Core
- **THEN** the memory bridge starts with matching tenant, bridge token, database schema revision, and application DSN

#### Scenario: Explicit configuration
- **WHEN** an explicit secret path or bridge enable setting is supplied
- **THEN** that setting takes precedence over the local default

### Requirement: Text memory readiness
The runtime SHALL support authoritative text memory with embedding and reranking disabled and report actual protected bridge/database readiness independently of model configuration.

#### Scenario: No embedding model requested
- **WHEN** PostgreSQL and the schema are ready but no embedding model is configured
- **THEN** formal memory records, review, deletion, restoration, and text retrieval are available with vector retrieval disabled

#### Scenario: Database unavailable
- **WHEN** the database or required schema cannot be reached
- **THEN** startup or readiness reports failure without substituting fake memory
