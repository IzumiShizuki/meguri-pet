## Purpose

Provides a dependable, accessible interaction for generated-file notifications so users can open or dismiss artifact bubbles without affecting the pet's other controls.

## ADDED Requirements

### Requirement: Generated-file bubbles SHALL expose a hit-testable open action

Each generated-file bubble MUST expose a dedicated interactive open action that is reachable by pointer and keyboard in both normal and overlay layouts. The action MUST retain the artifact's existing allow-listed local-path or loopback URL behavior.

#### Scenario: Pointer activation opens and removes a local artifact bubble

- **WHEN** the user clicks the main action of a bubble for an artifact with a valid local path
- **THEN** the desktop open action is invoked and the bubble is removed after the action succeeds

#### Scenario: Browser activation opens and removes a loopback artifact bubble

- **WHEN** the user clicks the main action of a bubble without a desktop open bridge but with a valid artifact URL
- **THEN** the artifact URL is opened in a new tab and the bubble is removed

#### Scenario: Keyboard activation follows the pointer contract

- **WHEN** the user focuses a bubble's main action and presses Enter or Space
- **THEN** the same open-and-remove behavior occurs and the browser's default Space scroll is prevented

### Requirement: Generated-file bubbles SHALL support independent dismissal

Each generated-file bubble MUST expose a visible dismiss action when the bubble is shown. Dismissal MUST remove only that bubble, MUST NOT invoke file opening, and MUST remain usable while the main open action is pending.

#### Scenario: Dismissal removes a bubble without opening the file

- **WHEN** the user activates the bubble's dismiss action
- **THEN** the bubble is removed and no artifact-open operation is invoked

#### Scenario: Failed opening leaves an actionable bubble

- **WHEN** the artifact-open operation fails
- **THEN** the bubble remains visible, an error is reported through the existing error surface, and the dismiss action remains available

### Requirement: Bubble rendering SHALL preserve artifact identity and accessibility

The bubble MUST keep the extension/icon presentation and expose an accessible name derived from the artifact name for both opening and dismissal actions. Icon replacement MUST NOT remove or disable either action.

#### Scenario: Asynchronous icon replacement preserves interaction

- **WHEN** the native artifact icon resolves after the bubble is rendered
- **THEN** only the visual face is replaced and both actions remain connected and activatable
