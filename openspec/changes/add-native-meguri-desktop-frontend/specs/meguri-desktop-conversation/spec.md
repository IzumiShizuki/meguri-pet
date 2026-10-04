## Purpose

Provide the first-party desktop frontend with a reliable conversation and artifact experience based on Meguri's canonical protocol rather than AIRI-specific providers, stores, or renderer lifecycle.

## ADDED Requirements

### Requirement: Desktop conversations use the canonical turn protocol
The frontend SHALL create turns through the local desktop gateway, consume ordered canonical events, append streaming text, and render terminal completion, cancellation, and failure states.

#### Scenario: A turn streams successfully
- **WHEN** the user sends a non-empty message and the Core emits ordered turn events
- **THEN** the frontend shows the user message immediately, incrementally renders the Meguri reply, and marks the reply complete after a terminal event

#### Scenario: The event stream reconnects
- **WHEN** the event stream is interrupted after accepting one or more events
- **THEN** the frontend resumes from its last accepted sequence or restores the session snapshot without duplicating visible text or one-time effects

#### Scenario: The turn fails
- **WHEN** the Core emits a failed terminal state or the conversation path cannot be recovered
- **THEN** the frontend retains the user message, presents a specific retryable error, and allows a later message or retry

### Requirement: Conversation identity persists locally
The frontend SHALL keep a stable local user identity and session identity across ordinary relaunches while allowing the user to explicitly begin a new conversation.

#### Scenario: Application relaunches
- **WHEN** persisted identity data is valid
- **THEN** the frontend reconnects using the same local identity and session checkpoint

#### Scenario: User starts a new conversation
- **WHEN** the user explicitly chooses to start a new conversation
- **THEN** the frontend creates a new session identity and separates its visible history and reducer checkpoint from the previous conversation

### Requirement: Generated artifacts appear as safe desktop actions
The frontend SHALL poll the trusted metadata-only artifact feed, de-duplicate entries, append generated files to conversation history, and show at most three dismissible floating file bubbles.

#### Scenario: A new generated file appears
- **WHEN** the artifact feed returns a previously unseen allowed file
- **THEN** the frontend adds a clickable history entry and a floating bubble representing the file

#### Scenario: User opens an artifact bubble
- **WHEN** the desktop host confirms that the file was opened successfully
- **THEN** the corresponding floating bubble is removed while the conversation-history entry remains

### Requirement: The migration surface supports staged feature parity
The frontend SHALL expose owned extension points for attachments and resource selection, daily briefings, document-edit approval, settings, voice input, and alternate character renderers without requiring AIRI stores.

#### Scenario: A later parity feature is introduced
- **WHEN** a later migration slice adds one of the declared feature families
- **THEN** it integrates through Meguri-owned state and host interfaces rather than importing AIRI application state
