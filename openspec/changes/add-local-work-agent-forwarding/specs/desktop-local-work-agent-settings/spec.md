## Purpose

让 Meguri 用户能够在桌面设置窗口中查看本机 Codex 与 DeepSeek Harness 的可用状态、显式启用任务转交，并在需要时让 Meguri 启动 Harness 网页宿主，而不暴露任何凭据。

## ADDED Requirements

### Requirement: Settings report local install facts without credentials

The desktop SHALL expose a work-agent settings section that reports each executor's install, sign-in and work-preset state, and MUST NOT return, log or persist a credential value.

#### Scenario: Opening the settings without any work agent
- **WHEN** no Codex or Harness installation is found and the user opens the settings
- **THEN** the section SHALL report both executors as not found and SHALL keep forwarding disabled by default

#### Scenario: A Codex installation exists
- **WHEN** a local Codex CLI and its account data exist
- **THEN** the section SHALL report the CLI as found and the sign-in state as confirmed or unconfirmed without reading account secrets

#### Scenario: A Harness installation exists
- **WHEN** a local Harness CLI exists
- **THEN** the section SHALL report the CLI state, whether a DeepSeek key reference exists, and whether the Meguri work preset is present, missing or belongs to the user

### Requirement: Enabling forwarding is an explicit user action

Forwarding SHALL remain off until the user enables it, and reading the settings MUST NOT write anything into a work agent's home directory.

#### Scenario: Opening the settings page
- **WHEN** the user opens the work-agent section
- **THEN** the desktop SHALL NOT create or modify any file inside the Harness home

#### Scenario: The user enables forwarding
- **WHEN** the user saves an enabled configuration
- **THEN** the desktop SHALL install the Meguri work preset if and only if that exact file is absent, and SHALL report the outcome

#### Scenario: The user disables forwarding
- **WHEN** the user saves a disabled configuration
- **THEN** the desktop SHALL stop the observation loop and SHALL disable the Harness auto-start option

### Requirement: Meguri may host the Harness web service only on request

The desktop SHALL start a Harness web host only when the user explicitly opted in, SHALL write its launcher output to a private service log, and SHALL stop the host when Meguri exits.

#### Scenario: Auto-start is enabled
- **WHEN** the user enabled both forwarding and Harness auto-start
- **THEN** the desktop SHALL start a Harness web host bound to loopback and SHALL record its launcher line only in the private service log

#### Scenario: Auto-start is disabled
- **WHEN** the user enabled forwarding but not auto-start
- **THEN** the desktop SHALL NOT spawn a Harness process and SHALL still connect to a Harness host the user started themselves

#### Scenario: Meguri quits
- **WHEN** Meguri exits
- **THEN** it SHALL stop a Harness host it started and SHALL close its Codex child process and receipt database

### Requirement: Connection testing is explicit and non-destructive

The desktop SHALL contact the selected executors only when the user requests a connection test, and the test MUST NOT send, queue or cancel any task.

#### Scenario: The user requests a test
- **WHEN** forwarding is enabled and the user requests a connection test
- **THEN** the desktop SHALL probe Codex and Harness and SHALL report each executor's reachability with an actionable message

#### Scenario: Forwarding is disabled
- **WHEN** the user requests a test while forwarding is disabled
- **THEN** the desktop SHALL refuse with a message telling the user to enable forwarding first
