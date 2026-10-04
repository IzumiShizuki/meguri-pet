## Purpose

Allow Meguri to browse and import public ModelScope Skills through a constrained source boundary without adopting another Agent runtime or installer.

## ADDED Requirements

### Requirement: ModelScope public catalog is available behind an opt-in source
The system SHALL keep `meguri.skills.modelscope.enabled` disabled by default. When enabled, it SHALL provide paginated public Skill search and detail from `https://modelscope.cn` without requiring a token.

#### Scenario: Source is disabled
- **WHEN** the feature flag is false
- **THEN** browse, import, and update operations return a stable disabled error while installed local revisions remain usable

#### Scenario: Public Skill detail is returned
- **WHEN** an authorized user requests a public Skill detail while the source is enabled
- **THEN** the response includes stable external ID, description, license, requirements metadata, and update identity without exposing private credentials

### Requirement: Download uses only the constrained Python bridge
The system SHALL fetch public Skill packages through an internal Python bridge backed by `modelscope-hub>=0.2,<0.3` and SHALL NOT install `ms-agent`, execute shell installers, access private Skills, publish Skills, or synchronize collections.

#### Scenario: Public package is fetched
- **WHEN** Java Core requests a supported public Skill through the authenticated internal bridge
- **THEN** the bridge downloads to a scoped staging directory and returns bounded metadata for Java validation

#### Scenario: Partial download fails safely
- **WHEN** the bridge times out or returns an incomplete package
- **THEN** no revision is activated and the staging failure is reported without altering existing revisions

### Requirement: Source failures are isolated from installed Skills
Remote timeouts, 404 responses, malformed payloads, or bridge unavailability SHALL affect only browse, import, and update operations.

#### Scenario: ModelScope is unavailable
- **WHEN** ModelScope cannot be reached
- **THEN** already installed and enabled revisions remain readable in eligible AGENT Turns
