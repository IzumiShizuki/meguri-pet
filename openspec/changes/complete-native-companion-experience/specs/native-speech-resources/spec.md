## Purpose

Make the existing local speech engine manageable from the desktop with verified voice-resource lifecycle operations and independent playback preferences.

## ADDED Requirements

### Requirement: Local speech model lifecycle
The app SHALL show the active local engine and model status and support import, activation, download from an explicitly selected HTTPS resource manifest and removal of compatible managed voice packages. Packages MUST identify the required runtime, references and integrity hashes. Unsupported model formats MUST be rejected, and the pre-existing environment/default voice MUST not be deleted or overwritten.

#### Scenario: Import and activate a voice
- **WHEN** a compatible complete voice profile is imported and activated
- **THEN** its required weights and reference audio are verified and synthesis uses the selected profile.

#### Scenario: Download fails or package is incompatible
- **WHEN** a download fails integrity verification or an unsupported runtime is imported
- **THEN** the previous active voice remains usable and the app explains the failure.

### Requirement: Playback preferences
The app SHALL offer a 0.5x to 2.0x speed setting, volume, quick mute and separate desktop-pet/Galgame playback switches. Replies and local reminders MUST use the effective preferences, preserve text on synthesis errors and avoid replaying already accepted speech events.

#### Scenario: Independent playback
- **WHEN** desktop speech is enabled and Galgame speech is disabled
- **THEN** replies in each surface follow the corresponding switch and use the selected speed and volume.

#### Scenario: Local engine unavailable
- **WHEN** synthesis is unavailable
- **THEN** text delivery remains successful and the voice settings identify the unavailable service.
