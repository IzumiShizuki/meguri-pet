## Purpose

Provide an opt-in prompt-only capability that teaches capable assistant routes how to refer to already-approved attachments and generated files without granting file-reading, file-writing, or agent authority.

## ADDED Requirements

### Requirement: Prompt-only approved-file reference capability
The system SHALL expose an opt-in prompt skill describing the approved attachment and generated-artifact reference format. The skill MUST not grant filesystem access, invoke an agent, or become required for ordinary or fast conversational turns.

#### Scenario: Eligible capability plan includes the skill
- **WHEN** a capability plan explicitly exposes the approved-file reference skill
- **THEN** the model context SHALL explain how to refer to already-approved files and generated artifacts without claiming access to other local files

#### Scenario: Ordinary conversation omits the skill
- **WHEN** a normal conversation plan does not expose the skill
- **THEN** the conversation SHALL retain existing planning and agent behavior while verified artifact handling remains available in the runtime
