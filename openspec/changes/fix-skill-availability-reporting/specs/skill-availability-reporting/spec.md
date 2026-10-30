## Purpose

Explain current Skill usability accurately without granting authority or confusing discovery with execution.

## ADDED Requirements

### Requirement: Derive readiness from current dependencies and bindings
The system SHALL retain import validation evidence while checking current dependencies for activation and new turn snapshots. DISABLED bindings MUST NOT project an active revision. A turn MUST distinguish missing dependencies from dependencies not exposed in its frozen capabilities, without inferring approval from exposure alone.

#### Scenario: Dependency changes after import
- **WHEN** a dependency is registered or removed after import
- **THEN** current readiness and new activation/turn decisions reflect the change, while stored import reports and existing frozen snapshots remain unchanged

#### Scenario: Disable and re-enable
- **WHEN** an enabled Skill is disabled and retains its selected digest
- **THEN** it is inactive and the same revision can be explicitly enabled again

### Requirement: Preserve safe outcomes through chat
The system SHALL carry known application-owned public-read failure codes and retryability through Capability and ReAct to chat context. It MUST NOT replace a failed read with unrelated success data or disclose raw error bodies, credentials or arbitrary exception messages. Discovery MUST NOT imply installation or callability.

#### Scenario: Public read fails then recovers
- **WHEN** a public source or fetch fails and a later independent call succeeds
- **THEN** the first observation and chat context retain the safe failure reason and the later observation carries the actual structured result

#### Scenario: Tool requires approval
- **WHEN** the executor rejects a tool for required approval
- **THEN** the chat context preserves the approval reason and no implementation is invoked

#### Scenario: Explicitly approved MCP read returns data
- **WHEN** a scoped MCP READ_TOOL with external side effects has an approval matching the exact frozen proposal
- **THEN** ReAct can execute through the existing authority check and pass the untrusted result into chat context; absent, forged, pending, declined or mismatched approval cannot execute

#### Scenario: MCP tool omits outputSchema
- **WHEN** a tool omits the optional outputSchema
- **THEN** its result object remains accepted as untrusted data, while explicitly declared output schemas continue to be validated
