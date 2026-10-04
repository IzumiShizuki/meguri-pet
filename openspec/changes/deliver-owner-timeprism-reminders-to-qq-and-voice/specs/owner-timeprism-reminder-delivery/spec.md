## Purpose

Deliver TimePrism Todo reminders for the selected main Shizuki account to the desktop and its configured QQ private chat, with spoken alerts and visible delivery state.

## ADDED Requirements

### Requirement: Main account binding
The system SHALL bind enhanced reminder delivery to one selected Shizuki user ID. A later login with a different ID MUST NOT send that account's Todo details to the bound QQ destination or speak them as the main account.

#### Scenario: Selected account triggers
- **WHEN** the selected main account has an enabled Todo reminder due while Meguri is running
- **THEN** the desktop shows its reminder and starts the configured speech and QQ delivery paths

#### Scenario: Different account logs in
- **WHEN** a different Shizuki account is connected
- **THEN** its Todo reminders MUST NOT enter the main account's QQ delivery path

### Requirement: Desktop spoken reminder
For a main account reminder, the system SHALL attempt local spoken playback when desktop speech is enabled. A speech failure MUST NOT suppress the reminder card or system notification.

#### Scenario: Speech enabled
- **WHEN** a main account reminder fires and the local speech service is available
- **THEN** Meguri reads a short reminder containing the Todo title

#### Scenario: Speech unavailable
- **WHEN** speech synthesis or playback fails
- **THEN** the card remains visible and the failure does not mark QQ delivery complete

### Requirement: QQ proactive delivery
The system SHALL send each main account Todo occurrence to one explicitly configured QQ private message origin through AstrBot, with a stable occurrence ID and durable retry state. It MUST NOT accept a message target from Todo content or the desktop event payload. Once a successful send is durably recorded, subsequent polls MUST NOT resend it.

#### Scenario: Successful delivery
- **WHEN** the desktop publishes a due occurrence and AstrBot sends it successfully
- **THEN** repeat polls and desktop retries do not produce a second QQ message after successful delivery is recorded

#### Scenario: Transient failure
- **WHEN** the remote Core or QQ delivery is unavailable
- **THEN** the pending occurrence remains retryable and the UI does not claim successful QQ delivery

#### Scenario: Unconfigured QQ target
- **WHEN** no owner QQ private message origin is configured
- **THEN** no proactive QQ message is sent and setup is shown as incomplete

### Requirement: Account and event isolation
The remote reminder feed SHALL be authenticated, validate bounded reminder fields, and return only the selected owner's occurrences to its configured AstrBot consumer. The desktop MUST NOT expose Core credentials or Shizuki tokens to the renderer.

#### Scenario: Invalid event
- **WHEN** an unauthenticated or malformed reminder event is submitted
- **THEN** the Core rejects it without storing or delivering it
