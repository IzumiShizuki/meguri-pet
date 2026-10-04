## Purpose

Preserve the recognizable AIRI desktop-pet interaction model while moving the visible product into Meguri-owned windows, state, and protocol adapters.

## ADDED Requirements

### Requirement: The stage is frameless and title-bar free
The native Meguri stage MUST use a transparent frameless host and SHALL NOT present a persistent title bar, app-title row, or status header across the top of the character stage.

#### Scenario: Stage becomes ready
- **WHEN** the native renderer is ready and the startup view transitions to the stage
- **THEN** the character appears directly on the desktop with only floating controls and content surfaces over transparent regions

#### Scenario: Runtime status changes
- **WHEN** Meguri becomes busy or reports a recoverable error
- **THEN** status is shown inside an unobtrusive floating control or transient notice rather than adding a title bar

### Requirement: Floating controls follow the AIRI interaction hierarchy
The stage SHALL provide a bottom-right controls island that remains compact by default and expands on demand to expose working desktop actions.

#### Scenario: Controls are collapsed
- **WHEN** the stage first appears or the user collapses the controls
- **THEN** only the move affordance, speech affordance, and expand affordance remain visible without spanning the window width

#### Scenario: User expands controls
- **WHEN** the user activates the expand affordance or opens the character context controls
- **THEN** the island reveals settings, chat, outfit, appearance, always-on-top, centering, refresh, and window visibility actions

#### Scenario: User invokes an available control
- **WHEN** the user selects a control backed by a Meguri capability
- **THEN** the corresponding action occurs and the control communicates its current state

### Requirement: Embedded chat matches the AIRI fork presentation
The embedded chat SHALL be positioned 12 pixels from the left, right, and bottom edges, SHALL use an 11.5rem compact height and a 55vh expanded height, and SHALL use AIRI's warm neutral light palette and stone dark palette rather than the previous purple glass treatment.

#### Scenario: Compact chat is visible
- **WHEN** chat visibility is enabled and history is not expanded
- **THEN** the chat occupies the AIRI-familiar bottom dock position and compact height without covering the controls island

#### Scenario: Chat history expands
- **WHEN** the user expands history
- **THEN** the dock grows to 55vh while retaining its bottom and side insets and preserving composer access

#### Scenario: Theme changes
- **WHEN** the user selects light, dark, or system appearance
- **THEN** the chat switches between warm cream/amber light surfaces and stone/amber dark surfaces with readable text and focus states

### Requirement: Settings open as a functional independent window
The controls island SHALL open a reusable settings window separate from the transparent character stage, and settings changes SHALL apply to the active stage without requiring application restart unless explicitly stated.

#### Scenario: User opens settings
- **WHEN** the user activates the settings control
- **THEN** an existing settings window is restored or a new one is opened and focused without hiding the stage

#### Scenario: User changes a supported preference
- **WHEN** the user changes appearance, chat, character, window, speech, or shortcut preferences
- **THEN** the preference is persisted locally and the relevant visible or host behavior updates

#### Scenario: User inspects local services
- **WHEN** the user opens the service-status section
- **THEN** settings reports the current gateway, Core, TTS, asset, and renderer readiness without exposing secrets

### Requirement: Unsupported AIRI settings are not presented as working features
The native settings interface MUST omit AIRI-only provider, plugin, updater, and advanced model-renderer options until Meguri owns corresponding runtime capabilities.

#### Scenario: User browses all settings categories
- **WHEN** the user reviews the native settings navigation
- **THEN** every editable option has an implemented Meguri effect and unavailable AIRI feature families are not represented by inert controls
