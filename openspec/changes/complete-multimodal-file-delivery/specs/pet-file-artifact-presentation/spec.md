## Purpose

Make safe files produced for a desktop-pet conversation immediately useful by showing them as clickable chat links and short-lived, spatial pet-side artifacts.

## ADDED Requirements

### Requirement: Safe model-returned file references
The system SHALL recognize only existing regular files beneath the configured generated-output roots when a model explicitly returns a generated artifact reference or a matching generated-file path. It MUST ignore arbitrary paths, symlinks that escape those roots, and unsafe executable file types.

#### Scenario: Model returns an approved generated artifact
- **WHEN** a model response references an existing allowed file under a generated-output root
- **THEN** the response stream SHALL include a verified artifact reference with a display label and local-open target

#### Scenario: Model returns an arbitrary local path
- **WHEN** a model response contains a path outside the generated-output roots or a non-passive file type
- **THEN** the system SHALL not expose it as an artifact link or local-open target

### Requirement: Clickable and floating file presentation
The desktop-pet UI SHALL render every verified model-returned artifact as blue clickable link text and SHALL show a short-lived bubble at a randomized safe position around the pet. Activating either affordance SHALL use the operating system's default application after the existing local artifact validation succeeds.

#### Scenario: Generated artifact reaches the desktop UI
- **WHEN** a verified artifact is emitted for a response
- **THEN** the UI SHALL make its label a blue clickable link and display one floating bubble without blocking the chat interaction

#### Scenario: User opens an artifact
- **WHEN** the user activates a verified link or bubble
- **THEN** the UI SHALL request the desktop main process to open the validated local file with its default application
