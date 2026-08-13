## Purpose

Provide a versioned, provenance-aware compressed representation of older conversation history without replacing the immutable Message DAG or treating derived summaries as formal memory.

## ADDED Requirements

### Requirement: Structured summaries remain backward compatible
The system SHALL accept legacy summaries that contain only the existing content and source message fields, and SHALL persist new summaries with a versioned structured projection containing a gist, current state, decisions, constraints, open threads, exact items, and fact-level provenance.

#### Scenario: Legacy summary is restored
- **WHEN** a persisted summary has no structured fields
- **THEN** the system restores it as a legacy summary block and does not attempt automatic fact-based rehydration from it

#### Scenario: Structured summary is restored
- **WHEN** a persisted summary contains the structured schema
- **THEN** every fact retains its text, source message IDs, importance, status, and supersession links

### Requirement: Fact provenance and supersession are authoritative only as derived data
The system SHALL associate every structured fact with existing source message IDs and SHALL represent superseded facts explicitly without deleting or changing source messages.

#### Scenario: A newer decision replaces an older decision
- **WHEN** a refactoring result marks a fact as superseding another fact
- **THEN** the compact projection identifies the newer fact as current and retains both source links for audit and recovery

#### Scenario: Source message changes branch validity
- **WHEN** a source message is no longer on the active path or its revision digest changes
- **THEN** the related structured summary becomes stale and is excluded from active context

### Requirement: Refactoring strategy has a safe deterministic fallback
The system SHALL use a deterministic offline-compatible refactoring strategy by default and SHALL accept an optional semantic strategy whose timeout, invalid output, or unavailable provider result falls back to the deterministic result.

#### Scenario: Semantic compressor times out
- **WHEN** the optional semantic strategy does not finish within its worker budget
- **THEN** the worker records a recoverable failure or fallback outcome and does not affect the completed Turn or raw message graph

#### Scenario: Semantic compressor returns invalid facts
- **WHEN** structured output has missing sources, invalid status, or an unknown schema revision
- **THEN** the result is rejected and the deterministic strategy remains the usable summary

### Requirement: Precompression is idempotent and does not recursively overwrite history
The system SHALL key precompression work by conversation graph revision, model/strategy revision, and source range, and SHALL materialize independent summary versions without replacing earlier summaries or summarizing a summary as raw authority.

#### Scenario: The same graph is queued twice
- **WHEN** the same conversation revision and strategy revision are queued more than once
- **THEN** only one durable job is processed

#### Scenario: A newer graph revision exists
- **WHEN** a worker processes a job whose source graph is no longer the current active branch
- **THEN** the job is rejected as stale and no sibling branch content enters the summary
