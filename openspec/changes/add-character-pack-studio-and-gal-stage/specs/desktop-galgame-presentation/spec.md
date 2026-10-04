## Purpose

Present an ongoing native desktop Meguri conversation in a full-window Galgame surface while preserving the same authoritative session, streaming message history, and presentation state as ambient desktop mode.

## ADDED Requirements

### Requirement: Users can switch between ambient and Galgame presentation surfaces
The desktop client SHALL let users enter and exit a Galgame presentation surface from the native desktop stage. Surface switching MUST preserve the active conversation session, messages, streaming checkpoint, current turn, and presentation state.

#### Scenario: Entering Galgame mode during a conversation
- **WHEN** a user enters Galgame mode while a Meguri conversation is active
- **THEN** the Galgame surface displays the same conversation and current character presentation without creating a new session or replaying prior messages

#### Scenario: Returning to ambient mode
- **WHEN** a user exits Galgame mode
- **THEN** the desktop client returns to the ambient stage while retaining the same conversation state and latest resolved presentation

### Requirement: Galgame mode supports text-first conversation interaction
The Galgame surface SHALL display streaming text, conversation history, a text composer, cancellation controls, and the current presentation state. Character rendering, voice, lip-sync, or background availability MUST NOT prevent the user from reading, sending, retrying, or cancelling text conversation.

#### Scenario: Streaming text without a valid character asset
- **WHEN** a turn streams text while the active character asset cannot be rendered
- **THEN** the Galgame surface continues to display the text and conversation controls using a safe visual fallback

#### Scenario: Cancelling a turn from Galgame mode
- **WHEN** a user cancels an active turn from the Galgame surface
- **THEN** the client delegates cancellation to the existing conversation runtime and displays its terminal result in both presentation surfaces

### Requirement: Galgame mode owns foreground interaction while ambient mode retains pet behavior
The Galgame surface SHALL receive pointer and keyboard interaction as a normal foreground interface. Entering it MUST disable ambient click-through behavior, and returning to ambient mode MUST restore the existing ambient pointer policy.

#### Scenario: Entering the interactive Galgame surface
- **WHEN** a user opens Galgame mode
- **THEN** the desktop window stops passing pointer input through to the desktop and accepts keyboard focus for conversation input

#### Scenario: Closing the interactive Galgame surface
- **WHEN** a user returns to ambient mode
- **THEN** the desktop client restores ambient pointer behavior without altering the active conversation
