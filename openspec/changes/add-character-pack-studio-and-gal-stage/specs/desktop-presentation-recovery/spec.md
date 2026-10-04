## Purpose

Keep local desktop presentation effects coherent when users change surfaces, interrupt an interaction, or receive replayed conversation events, without allowing presentation failures to block the text conversation.

## ADDED Requirements

### Requirement: Presentation effects are applied against a stable local snapshot
The desktop client SHALL resolve visual presentation from a stable local snapshot for the duration of a rendered interaction. A catalog activation or resource change MUST apply only to subsequently resolved interactions and MUST NOT replace assets already attached to an active rendered interaction.

#### Scenario: Publishing a resource during an active response
- **WHEN** a user publishes or activates a different character pack while a response is being rendered
- **THEN** the active response retains its existing resolved presentation and a later interaction may use the newly active pack

### Requirement: Surface transitions do not duplicate one-shot presentation effects
The desktop client SHALL preserve the current visual state across ambient and Galgame surface transitions and MUST NOT replay an already consumed one-shot local presentation effect merely because the UI surface changed or rebuilt.

#### Scenario: Switching surfaces after a presentation cue
- **WHEN** a user changes from ambient mode to Galgame mode after a presentation cue has been consumed
- **THEN** the Galgame surface reconstructs the current state without replaying the consumed one-shot effect

### Requirement: Presentation failures degrade to text-safe interaction
The desktop client SHALL isolate local presentation failures from the conversation runtime. If a resource cannot be resolved or rendered, the client MUST retain text, history, sending, retrying, and cancellation behavior and expose a non-blocking fallback state.

#### Scenario: Renderer failure while reading a conversation
- **WHEN** the active resource renderer fails after a conversation has begun
- **THEN** the client retains the existing text conversation and replaces the failed presentation with a safe fallback state
