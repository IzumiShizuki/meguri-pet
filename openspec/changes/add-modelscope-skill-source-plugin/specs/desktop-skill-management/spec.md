## Purpose

Give desktop users a clear, scoped workflow to discover, inspect, validate, activate, update, disable, and roll back public external Skills.

## ADDED Requirements

### Requirement: Internal Skill APIs enforce read and manage scopes
Catalog browsing and inspection SHALL require `skill:read`; imports, activation, disablement, update import, and rollback SHALL require `skill:manage`. Responses SHALL never include secret values or unsafe filesystem paths.

#### Scenario: Read-only caller cannot activate
- **WHEN** a caller with `skill:read` but without `skill:manage` requests activation
- **THEN** the operation is denied and no state changes

### Requirement: Desktop Skills workspace covers the full managed lifecycle
The native desktop SHALL provide ModelScope search, detail, license and dependency inspection, import progress, validation report, incompatibility reasons, explicit enablement, update checks, disablement, and historical revision activation.

#### Scenario: User imports and enables a compatible Skill
- **WHEN** the user selects a public search result, imports it, reviews a passing report, and confirms enablement
- **THEN** the selected digest is shown as enabled and eligible for relevant AGENT Turns

#### Scenario: Incompatible Skill explains why
- **WHEN** a valid Skill has an unsupported tool or missing environment requirement
- **THEN** the desktop shows the exact compatibility issue and prevents activation

### Requirement: Local mock and PostgreSQL have equivalent observable behavior
Skill listing, revision state, activation, update identity, rollback, and audit SHALL behave consistently in the in-memory local profile and PostgreSQL profile.

#### Scenario: Restart restores PostgreSQL state
- **WHEN** Core restarts after an activated revision was persisted in PostgreSQL
- **THEN** the same revision remains active with its history and audit intact

### Requirement: Management UI is native and accessible
The Skills workspace SHALL be implemented in `desktop-meguri`, not the AIRI fallback, and SHALL provide keyboard access, readable status distinctions, responsive layout, reduced-motion support, and non-color-only validation cues.

#### Scenario: Keyboard-only lifecycle review
- **WHEN** a user navigates search results, a validation report, and activation controls using a keyboard
- **THEN** focus remains visible and every status and action remains understandable without relying only on color
