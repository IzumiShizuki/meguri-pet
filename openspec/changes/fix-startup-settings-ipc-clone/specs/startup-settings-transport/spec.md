## Purpose

Ensure selected startup preferences reach the desktop process reliably and users receive clear feedback about saving and applying their Windows startup configuration.

## ADDED Requirements

### Requirement: Startup selections cross the desktop boundary reliably
The settings form SHALL send the selected login and administrator booleans once per save, without serialization failure caused by UI state wrappers.

#### Scenario: Reactive settings draft is saved
- **WHEN** the user saves either combination of the two startup options
- **THEN** the desktop receives exactly the selected booleans in one request and the UI reflects the returned save and application status

### Requirement: Identical startup feedback is displayed once
The settings form SHALL display identical startup status and operation feedback only once and SHALL preserve distinct feedback messages.

#### Scenario: Save fails before application
- **WHEN** the desktop bridge rejects the save
- **THEN** the form shows one error message and permits retry without claiming that Windows applied the change

#### Scenario: Preference saved but Windows application is pending
- **WHEN** the desktop returns a saved preference with a pending Windows task status
- **THEN** the saved choice remains visible, pending application is reported once, and retry remains available
