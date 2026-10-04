## Purpose

Ensure desktop and SDK consumers can process Core turn-event and session-snapshot envelopes when optional protocol metadata is serialized using JSON-compatible nullable values.

## ADDED Requirements

### Requirement: Nullable optional extension identifier
The protocol event parser SHALL treat an omitted `required_extension` field and a `required_extension` field whose value is `null` as the same absence of an extension requirement.

#### Scenario: Core serializes no required extension as null
- **WHEN** a valid turn-event envelope contains `"required_extension": null`
- **THEN** the parser accepts the envelope and exposes no required extension identifier

#### Scenario: Core omits the optional extension field
- **WHEN** a valid turn-event envelope omits `required_extension`
- **THEN** the parser accepts the envelope and exposes no required extension identifier

### Requirement: Non-null extension validation remains strict
The protocol event parser MUST continue to require any non-null `required_extension` value to be a non-empty string.

#### Scenario: Valid extension identifier
- **WHEN** a valid turn-event envelope contains a non-empty string `required_extension`
- **THEN** the parser accepts and preserves that identifier

#### Scenario: Invalid extension value
- **WHEN** a turn-event envelope contains an empty string or a non-string, non-null `required_extension`
- **THEN** the parser rejects the envelope as invalid

### Requirement: Nullable optional snapshot error
The session snapshot parser SHALL treat an omitted turn `error` field and a turn `error` field whose value is `null` as the same absence of an error.

#### Scenario: Successful turn serializes error as null
- **WHEN** a valid session snapshot contains a completed turn with `"error": null`
- **THEN** the parser accepts the snapshot and exposes no turn error

#### Scenario: Present error remains strict
- **WHEN** a session snapshot turn contains a non-null `error` value
- **THEN** the parser accepts only a string value and rejects other types
