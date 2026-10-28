## Purpose

This capability gives users actionable, privacy-safe information when local Windows speech recognition cannot start or complete. It helps distinguish microphone, speech-engine, and helper-process failures without exposing recognized audio or raw runtime exceptions.

## ADDED Requirements

### Requirement: Actionable local recognition failures
Meguri SHALL report whether a Windows voice-recognition failure occurred while initializing the speech engine, opening the default audio input, performing recognition, or running the helper process. The report SHALL provide a safe, actionable next step and SHALL NOT expose raw exception text.
Meguri SHALL display the complete active recognition status within the chat dock, wrapping it across lines when needed instead of truncating it.

#### Scenario: Default microphone cannot be opened
- **WHEN** the local recognition helper cannot open the default audio input
- **THEN** Meguri identifies the audio-input failure and asks the user to check the Windows default microphone and desktop-app microphone permission

#### Scenario: Speech engine or recognition fails
- **WHEN** the local Windows speech engine fails to initialize or recognize speech
- **THEN** Meguri identifies a speech-engine failure and asks the user to retry or check the installed Chinese speech component

#### Scenario: Helper exits without a structured result
- **WHEN** the PowerShell helper exits abnormally without a valid structured result
- **THEN** Meguri reports a helper-process failure instead of mislabeling it as a microphone permission failure

#### Scenario: Failure diagnostics preserve privacy
- **WHEN** Meguri reports a local voice-recognition failure
- **THEN** it does not save or upload microphone audio and does not include raw exception messages in the user-facing diagnostic

#### Scenario: Long recognition messages remain visible
- **WHEN** a recognition status or error message is longer than the remaining horizontal space in the compact chat dock
- **THEN** Meguri wraps the full message below the action buttons so the user can read the entire text

#### Scenario: Windows PowerShell loads localized helper text
- **WHEN** Windows PowerShell 5.1 loads the local recognition helper
- **THEN** the helper's encoding preserves localized strings and the script reaches its structured initialization result
