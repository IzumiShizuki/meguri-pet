## Purpose

Restore safe local-resource selection and the intended floating-control placement in the Meguri-owned desktop interface while preserving AIRI-compatible interaction semantics.

## ADDED Requirements

### Requirement: The composer resolves AIRI-compatible resource markers
The native desktop composer SHALL recognize leading and embedded `@` resource references through the canonical input resolver, including quoted and braced names with spaces, and MUST treat `@@` as an escaped ordinary at-sign.

#### Scenario: User types a resource reference
- **WHEN** the user enters `@文件名`, `@{带空格的名称}`, `@"带空格的名称"`, or an equivalent embedded reference
- **THEN** the composer enters local-resource selection with the resolver-provided query and preserves any surrounding message text

#### Scenario: User escapes the marker
- **WHEN** the user enters a message beginning with `@@`
- **THEN** the composer treats it as ordinary text and does not open Everything search

### Requirement: Everything candidates remain metadata-only until send
The desktop interface SHALL search through the existing loopback resource boundary, SHALL expose only candidates accepted by the local resource safety policy, and MUST NOT read candidate file contents merely because a query or selection occurs.

#### Scenario: Safe matches are returned
- **WHEN** the local Everything service returns safe matches for the resolved query
- **THEN** the composer shows at most eight candidates with their names and paths and identifies the selection as metadata-only

#### Scenario: User selects and reviews resources
- **WHEN** the user selects one or more candidates
- **THEN** each unique selection appears as a removable resource chip and the message is not sent automatically

#### Scenario: User sends selected resources
- **WHEN** the user explicitly sends a message with selected resources
- **THEN** the turn request includes the validated local-file attachment records and the visible conversation history identifies the referenced filenames

#### Scenario: User removes a selected resource
- **WHEN** the user removes a resource chip before sending
- **THEN** that resource is excluded from the turn request

### Requirement: Resource search is resilient and keyboard accessible
The resource picker SHALL communicate loading, empty, unavailable, and failure states, SHALL prevent stale responses from replacing newer results, and SHALL support keyboard selection without blocking ordinary message entry.

#### Scenario: Query changes during a search
- **WHEN** the user changes or removes the active `@` reference before an earlier request completes
- **THEN** the obsolete request is cancelled or ignored and its results are not displayed

#### Scenario: Search is unavailable or has no match
- **WHEN** Everything is unavailable, the gateway fails, or no safe candidate matches
- **THEN** the picker shows a specific recoverable message while preserving the draft and any already selected resources

#### Scenario: User operates the picker from the keyboard
- **WHEN** the candidate list is open and the user presses Up, Down, Enter, or Escape
- **THEN** focus selection moves, the highlighted candidate can be selected, or the candidate list closes without sending the message

### Requirement: Compact desktop controls are anchored at the upper-right
The three compact move, speech, and expand controls SHALL remain grouped at the upper-right of the frameless stage, and their expanded drawer SHALL open from the same upper-right anchor without moving the compact rail.

#### Scenario: Controls are collapsed
- **WHEN** the native stage is visible with controls collapsed
- **THEN** the three compact controls appear eight pixels from the top and right edges rather than at the lower-right

#### Scenario: Controls expand
- **WHEN** the user expands the controls island
- **THEN** the drawer appears to the left of the upper-right rail and remains usable within the stage viewport

### Requirement: Meguri inherits the AIRI fork's global shortcuts
The desktop host SHALL register the Meguri-specific AIRI fork shortcuts globally so they remain usable when another application has focus, while preserving the separate configurable visibility shortcut.

#### Scenario: User toggles chat globally
- **WHEN** the user presses `Ctrl+Alt+H`
- **THEN** the embedded Meguri chat dock toggles visibility

#### Scenario: User centers the stage globally
- **WHEN** the user presses `Ctrl+Alt+C` or `Ctrl+Alt+Shift+M`
- **THEN** the stage window moves to the center of its current display

#### Scenario: User switches outfits globally
- **WHEN** the user presses `Ctrl+Alt+[` or `Ctrl+Alt+]`
- **THEN** Meguri switches to the previous or next outfit respectively, wrapping across the supported outfit list

### Requirement: A pinned stage fades and passes through on hover
When always-on-top and fade-on-hover are enabled, the desktop stage SHALL fade the portrait under the pointer and allow pointer input to reach the application behind passive stage regions. Chat, controls, skills, artifacts, form controls, and resize edges MUST remain recoverably interactive.

#### Scenario: Cursor enters the portrait
- **WHEN** the pinned stage is visible and the cursor moves over the rendered portrait outside interactive UI
- **THEN** the portrait fades out and the stage ignores mouse clicks so the covered application can be clicked

#### Scenario: Cursor moves over transparent stage background
- **WHEN** the pinned stage is visible and the cursor is over a passive transparent region
- **THEN** the stage ignores mouse clicks without hiding interactive UI elsewhere

#### Scenario: Cursor reaches interactive UI
- **WHEN** forwarded pointer movement reaches chat, compact controls, an expanded panel, an artifact, or a resize edge
- **THEN** the stage restores normal mouse handling before the user clicks

#### Scenario: Fade-on-hover or always-on-top is disabled
- **WHEN** either feature is disabled
- **THEN** the portrait remains visible and the stage restores normal mouse handling
