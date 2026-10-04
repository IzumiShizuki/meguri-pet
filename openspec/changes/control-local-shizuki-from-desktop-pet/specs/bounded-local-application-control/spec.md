## Purpose

Define a narrow capability-policy boundary for user-requested, reversible interaction with trusted applications on the same machine without weakening approval requirements for durable writes or remote effects.

## ADDED Requirements

### Requirement: Local controls are a distinct capability kind
The capability runtime SHALL represent bounded local application interactions as `LOCAL_CONTROL_TOOL` capabilities distinct from read tools, durable write tools, and remote agents. A local-control capability MUST declare an external side effect, no secondary approval requirement, no network access, and an explicit server-authorized scope.

#### Scenario: Valid local control is exposed
- **WHEN** a healthy local-control capability has the required server-authorized scope and the Turn is eligible for bounded tool execution
- **THEN** the capability is exposed to the planner with its explicit identifier and bounded input schema

#### Scenario: Invalid local-control descriptor is proposed
- **WHEN** a local-control capability permits network access, omits its scope, declares a non-external side effect, or requires an approval mode incompatible with direct local control
- **THEN** registration or action validation fails closed before the implementation runs

### Requirement: Explicit user requests authorize reversible local interaction
The runtime SHALL allow a validated local-control action to execute without an additional approval round when the current user Turn explicitly requests the action and server policy has granted its scope. This authorization MUST NOT apply to durable write tools, shell or filesystem operations, or remote network capabilities.

#### Scenario: User asks for a scoped local interaction
- **WHEN** the user explicitly asks the pet to perform an exposed local application action and the local-control scope is authorized
- **THEN** the validated action executes directly and returns an auditable capability result

#### Scenario: Scope is absent
- **WHEN** a Turn requests local application control but server policy has not granted the capability's scope
- **THEN** the capability is not exposed and the local application is not contacted

#### Scenario: Durable write still requires approval
- **WHEN** a model proposes a durable `WRITE_TOOL` action without a matching resolved approval and idempotency contract
- **THEN** the runtime rejects it under the existing write-tool policy even if local controls are enabled

### Requirement: Local controls expose semantic operations only
Every local-control capability SHALL bind one explicit semantic operation or a closed input schema. It MUST NOT accept arbitrary executable text, shell commands, JavaScript, filesystem paths, DOM selectors, or unrestricted URLs.

#### Scenario: Planner receives local controls
- **WHEN** local controls are projected into the planner context
- **THEN** each operation is represented by a stable capability identifier and bounded argument schema rather than a free-form command surface
