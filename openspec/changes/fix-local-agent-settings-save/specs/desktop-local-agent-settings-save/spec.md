## Purpose

Let users reliably save local work-agent preferences in the trusted desktop settings window and distinguish invalid input, transport failure, persistence failure and successful readback without exposing credentials.

## ADDED Requirements

### Requirement: Trusted reactive settings can be saved
The desktop SHALL accept valid work-agent form values from its current trusted settings window, persist them, update the active runtime and display a successful save. Other windows and origins MUST NOT gain save access.

#### Scenario: Enable forwarding without starting a host
- **WHEN** the user enables task forwarding and saves with Harness auto-start off
- **THEN** the saved and active settings SHALL enable forwarding without launching a host
- **AND** closing and reopening settings SHALL show those values

#### Scenario: Untrusted source
- **WHEN** another window or an unexpected origin requests a save
- **THEN** no configuration or active state SHALL change

### Requirement: Failed settings saves preserve committed data
Invalid inputs and failed persistence SHALL produce readable errors and MUST NOT partially overwrite the previously committed configuration or update the running state from uncommitted values. A failure before sending MUST NOT be presented as a filesystem permission diagnosis.

#### Scenario: Invalid local directory
- **WHEN** a supplied Harness directory is relative, remote or otherwise invalid
- **THEN** saving SHALL fail with a directory validation message and preserve the prior configuration

#### Scenario: File replacement fails
- **WHEN** a new configuration cannot replace the committed file
- **THEN** the prior file SHALL remain complete and the active configuration SHALL remain unchanged

#### Scenario: Consecutive saves and legacy settings
- **WHEN** valid partial saves arrive consecutively against a compatible older configuration
- **THEN** the final document SHALL remain valid and preserve fields not changed by each request

### Requirement: Explicit connection testing uses saved state
Connection testing SHALL use the saved running settings and MUST NOT submit, cancel or retry tasks.

#### Scenario: Test after successful save
- **WHEN** the user saves enabled forwarding and requests a connection test
- **THEN** both executor probe results SHALL be displayed without task execution
