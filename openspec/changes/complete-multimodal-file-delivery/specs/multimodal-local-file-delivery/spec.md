## Purpose

Allow a desktop-pet conversation to send user-approved local visual files to a capable language model without exposing arbitrary local files or silently losing the requested content.

## ADDED Requirements

### Requirement: Explicit local multimodal attachments
The system SHALL accept an image dropped into the conversation or selected with the attachment control, and SHALL accept an image or PDF selected through the `@` local-resource picker, as a multimodal attachment only after the user sends the turn. It MUST reject an unsupported file type, an oversized attachment, or a local path that fails the safe-local-resource policy with a user-visible error.

#### Scenario: User drops a supported image
- **WHEN** a user drops a supported image onto the desktop-pet conversation and sends the turn
- **THEN** the system SHALL deliver the image content with the user's text to the selected multimodal model route

#### Scenario: User selects a local PDF through the resource picker
- **WHEN** a user selects a safe PDF using `@` and sends the turn
- **THEN** the system SHALL revalidate the local path and deliver the PDF content without granting the model arbitrary filesystem access

#### Scenario: Unsupported or unsafe attachment
- **WHEN** a selected attachment is not an allowed multimodal file or no longer passes local-path safety validation
- **THEN** the system SHALL fail the turn with a clear explanation and SHALL NOT substitute a metadata-only request

### Requirement: Configured multimodal model fallback
The system SHALL use the configured primary model for a multimodal turn only when that model is configured as multimodal-capable. Otherwise it SHALL route the turn to the configured `dsv4p` fallback using the fallback's explicit settings or the primary provider credentials when fallback credentials are absent.

#### Scenario: Primary model is declared multimodal-capable
- **WHEN** a multimodal attachment is sent and the active primary model is in the configured capability list
- **THEN** the system SHALL send the turn through the primary model route

#### Scenario: Primary model is not declared multimodal-capable
- **WHEN** a multimodal attachment is sent and the active primary model is not in the configured capability list
- **THEN** the system SHALL use the configured `dsv4p` fallback model route

#### Scenario: No fallback is configured
- **WHEN** a multimodal attachment requires fallback but no fallback model is configured
- **THEN** the system SHALL return a user-visible configuration error and SHALL NOT send an altered text-only request

### Requirement: Local model-session routing
The desktop gateway SHALL keep an attachment-bearing conversation on the local Core for the lifetime of that desktop session, while retaining the existing remote route for sessions that never send a local multimodal attachment.

#### Scenario: First multimodal turn through the gateway
- **WHEN** a validated desktop session submits a turn containing a multimodal attachment
- **THEN** the gateway SHALL route that turn and later requests for the same session to the local Core without forwarding the remote-core credential
