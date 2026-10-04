## Purpose

Give Meguri an owned, recognizable desktop-pet presentation that remains familiar to users of the current AIRI fork while allowing future layout, branding, and window-interaction changes without AIRI internals.

## ADDED Requirements

### Requirement: The stage presents the current Meguri character state
The desktop stage SHALL display the current character asset and SHALL update expression, intensity, outfit, motion treatment, and speaking state from canonical Meguri events when those values are available.

#### Scenario: A semantic cue resolves a new sprite
- **WHEN** the active turn emits a canonical sprite or expression cue
- **THEN** the visible character updates to the resolved safe asset and reflects the accompanying motion treatment

#### Scenario: A cue is incomplete
- **WHEN** a cue omits a usable sprite or contains an unknown expression
- **THEN** the stage preserves the last valid character state or uses the configured neutral fallback

### Requirement: AIRI-familiar composition is preserved during migration
The first-party stage SHALL provide a transparent floating composition with a central character, compact floating controls, status feedback, generated-file bubbles, and a bottom conversation dock that can expand or collapse.

#### Scenario: User opens the conversation dock
- **WHEN** the user activates the chat control or its shortcut
- **THEN** the conversation dock becomes usable without replacing or navigating away from the character stage

#### Scenario: User needs more conversation history
- **WHEN** the user expands the compact conversation dock
- **THEN** the stage increases the history area while keeping the character and controls available

### Requirement: Desktop stage state survives ordinary restarts
The stage SHALL persist non-sensitive presentation preferences including dock expansion, dock visibility, outfit preference, and reduced-motion preference.

#### Scenario: User relaunches the application
- **WHEN** saved presentation preferences are valid
- **THEN** the stage restores those preferences without restoring transient errors or in-progress turn state as active work

### Requirement: The stage remains operable without animation
The stage MUST respect the operating-system reduced-motion preference and SHALL keep all controls and state changes understandable when motion effects are disabled.

#### Scenario: Reduced motion is enabled
- **WHEN** the operating system reports a reduced-motion preference
- **THEN** ambient, entrance, and character motion effects are removed or reduced while state updates remain visible
