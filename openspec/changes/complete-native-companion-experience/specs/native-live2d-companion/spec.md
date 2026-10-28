## Purpose

Allow the native desktop pet to display an interactive user-provided Live2D character through the existing AIRI renderer while preserving a usable PNG fallback.

## ADDED Requirements

### Requirement: Managed character resources
The application SHALL import a local Live2D model directory or archive into its managed storage, validate referenced resources and present an activatable model catalog. Imports MUST reject traversal, symlinks, external URLs and executable resources. Removal MUST affect only managed resources.

#### Scenario: Import a complete model
- **WHEN** the user selects a supported model with its required resources
- **THEN** the app validates and copies it, displays its name and allows activation.

#### Scenario: Reject an unsafe or incomplete model
- **WHEN** an import references an external path or lacks required resources
- **THEN** import fails with an actionable message and the current character remains available.

### Requirement: Interactive rendering and fallback
The application SHALL use the existing AIRI Live2D runtime to render an activated model, follow pointer movement, react to supported touch areas and coordinate supported expression, motion and speech cues. Reduced-motion preferences MUST be respected. An unavailable runtime or failed model MUST show a clear error and recover to PNG.

#### Scenario: Activate and interact
- **WHEN** a valid model is active and the pointer moves or a model touch area is selected
- **THEN** the renderer follows the pointer and runs available model reactions, including configured local speech feedback.

#### Scenario: Rendering fails
- **WHEN** loading or rendering the selected model fails
- **THEN** a visible status explains the failure and the pet remains usable with PNG.
