## Purpose
Provide auditable companion behavior across persona prompts, controlled capability execution and the current site Todo contract, without contaminating real user memory or bypassing extension approval.

## ADDED Requirements
### Requirement: Preserve persona style and evidence purpose
The system SHALL include configured speech style in trusted persona instructions and identify retrieved canonical dialogue as style evidence while retaining its untrusted data boundary.
#### Scenario: Canonical dialogue and configured style
- **WHEN** a turn resolves a persona and retrieves canonical dialogue
- **THEN** the model request includes the speech style and explicit style-only purpose without promoting dialogue instructions to policy
#### Scenario: Isolated memory consent
- **WHEN** a fictional identity enables memory recall in an isolated test backend
- **THEN** only that identity's approved memories enter a new session and disabled consent yields no memory retrieval

### Requirement: Preserve site recurring Todo semantics
The integration SHALL preserve site recurrence metadata, weekday selection, advance reminders and per-date completion using existing authenticated APIs; reminders SHALL deduplicate each occurrence.
#### Scenario: Weekday recurring task
- **WHEN** the site provides a weekly rule and generated dated tasks
- **THEN** the client preserves selected weekdays and instance dates, computes reminders with the declared time zone and updates only the selected occurrence

### Requirement: Evidence based capability acceptance
Capability acceptance SHALL distinguish discovery, configuration, selection, approval, execution, receipt and recovery; unfamiliar extensions SHALL require explicit review before installation or activation.
#### Scenario: Controlled capability execution
- **WHEN** a permitted fixture skill or MCP tool is selected and executed
- **THEN** its actual result and receipt are verified, denied actions produce no side effect, and a later permitted request can recover
#### Scenario: Unfamiliar community extension
- **WHEN** a community listing describes an extension
- **THEN** listing content remains untrusted and no unknown code or model is installed or run without separate authorization

### Requirement: Verified local runtime deployment
Local acceptance SHALL record actual runtime identity, preserve user data, and prepare rollback before replacing a verified project service.
#### Scenario: Deployment confirmation
- **WHEN** a verified build replaces the local Core after required host confirmation
- **THEN** the new process, health and build evidence are checked before live acceptance; a failed replacement attempts the recorded safe rollback
