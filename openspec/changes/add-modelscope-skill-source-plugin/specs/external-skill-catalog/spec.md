## Purpose

Provide a durable, auditable, digest-addressed catalog for external Skills while treating every imported package and instruction as untrusted data.

## ADDED Requirements

### Requirement: External Skill revisions have independent validation and activation state
The system SHALL store each imported revision immutably by SHA-256 digest with validation state `QUARANTINED`, `VALID`, or `INVALID`, activation state `DISABLED` or `ENABLED`, and trust `UNTRUSTED_EXTERNAL`. A revision SHALL begin quarantined and SHALL become enabled only after successful validation and explicit user confirmation.

#### Scenario: Import remains quarantined
- **WHEN** a public external Skill package is imported
- **THEN** its immutable revision is stored as quarantined and disabled until validation and confirmation complete

#### Scenario: Missing license requires confirmation
- **WHEN** a valid Skill has no declared license
- **THEN** local enablement requires an explicit warning confirmation and the Skill is marked ineligible for Meguri redistribution

### Requirement: Package validation fails closed
The system SHALL reject path traversal, absolute paths, symbolic links, duplicate or non-root `SKILL.md`, digest mismatch, configured size or file-count limit violations, and dangerous binary formats. Script files SHALL only be exposed as bounded text and SHALL never be executed by the Skill subsystem.

#### Scenario: Malicious archive is invalid
- **WHEN** an imported package contains a traversal entry, symbolic link, second `SKILL.md`, or dangerous executable
- **THEN** the revision becomes invalid with stable failure codes and no file is exposed to the Agent

### Requirement: Requirements only check existing authority
The system SHALL map `web_search` to `legacy.web.search`, mark `terminal`, `shell`, and `code_executor` unsupported, require all other tool names to exactly match an existing Capability ID, and check only whether declared environment variable names are configured. It SHALL never return secret values, auto-install dependencies, or honor `always: true`.

#### Scenario: Unsupported requirement prevents compatibility
- **WHEN** a Skill declares `requires.tools: [terminal]` or an unavailable exact Capability ID
- **THEN** the revision may remain valid but is reported incompatible and cannot be selected for a Turn

#### Scenario: Always is ignored
- **WHEN** a Skill declares `always: true`
- **THEN** validation records a warning and runtime selection remains query-driven

### Requirement: Updates and rollback preserve immutable history
The system SHALL import updates as new quarantined revisions, keep the active revision unchanged until explicit activation, and implement rollback by activating a previously validated digest. Existing Turns SHALL continue using their frozen revision.

#### Scenario: Update does not replace a running Turn
- **WHEN** a new revision is imported while a Turn uses the old active digest
- **THEN** the running Turn keeps the old digest and new Turns use the newly activated digest only after confirmation

### Requirement: Catalog lifecycle is auditable without content leakage
The system SHALL audit source, external ID, digest, actor, transition, timestamp, and failure code, and SHALL NOT record Skill body text or secret values.

#### Scenario: Failed validation is audited safely
- **WHEN** validation fails
- **THEN** audit records the affected digest and stable failure code without the rejected file body
