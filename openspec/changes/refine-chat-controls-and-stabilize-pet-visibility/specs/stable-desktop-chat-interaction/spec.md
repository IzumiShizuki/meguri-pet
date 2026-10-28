## Purpose

Keep desktop companion chat readable and reversible while ensuring hover hiding recovers reliably when the pointer leaves the pet or its native window.

## ADDED Requirements

### Requirement: Expanded chat has a unified action header
The desktop SHALL display expanded chat actions and companion controls together in the header without covering the message history. All existing actions SHALL remain reachable with accessible names and keyboard focus.

#### Scenario: Expand chat in the compact pet window
- **WHEN** the user expands chat
- **THEN** conversation actions, speech, more controls, and collapse appear in a single header and the history uses the available width

#### Scenario: Use the controls drawer
- **WHEN** the user opens more controls by mouse or keyboard
- **THEN** the drawer stays within the viewport and focused controls remain open until focus leaves or the user dismisses it

#### Scenario: Hide chat while its controls drawer is open
- **WHEN** the user hides expanded chat from the controls drawer
- **THEN** the outgoing chat immediately stops receiving pointer, keyboard, and native window-drag input while the floating controls become available
- **AND** only one controls rail is interactive throughout the exit animation

#### Scenario: Return to chat controls after switching layout
- **WHEN** the user collapses, re-expands, hides, or restores chat and then opens controls using the stage context menu
- **THEN** the controls for the current layout open without a stale floating rail or stale component reference
- **AND** Escape dismisses those controls without changing the chat layout

### Requirement: Temporary expansion preserves compact position
The desktop SHALL restore the compact pet position after temporary panel expansion, compensating for automatic screen-edge fitting and preserving intentional movement or resizing while expanded.

#### Scenario: Open and close near the right or bottom edge
- **WHEN** a temporary panel is fitted against a screen edge and then closed without manual movement
- **THEN** the compact pet returns to its previous position without accumulating drift over repeated cycles

#### Scenario: Move an expanded panel
- **WHEN** the user moves or resizes the expanded panel before closing it
- **THEN** the compact pet position incorporates the intentional portrait-anchor displacement while removing automatic fitting displacement

#### Scenario: Return through Galgame on another display
- **WHEN** a temporary expanded panel enters and exits Galgame before closing on a negative-coordinate or narrow display
- **THEN** the original compact position remains restorable and the window remains within the current work area

### Requirement: Hover hiding always recovers
The desktop SHALL restore the portrait when the native cursor leaves its bounds or polling cannot provide a usable cursor position, even if the renderer receives no mouseleave event. Galgame and interactive controls SHALL remain interactive.

#### Scenario: Cursor leaves a click-through window
- **WHEN** hover hiding is active and a subsequent native cursor sample is outside the window without a renderer mouseleave
- **THEN** the portrait becomes visible on the next completed poll

#### Scenario: Cursor polling fails
- **WHEN** polling fails or returns no usable position after the portrait is hidden
- **THEN** the portrait and normal interaction recover and subsequent polls continue

#### Scenario: Cursor returns to controls
- **WHEN** the cursor moves from the hidden portrait onto a chat or companion control
- **THEN** the portrait becomes visible and the control receives mouse input
