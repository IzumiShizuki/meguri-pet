## Why

The Meguri microphone control currently reports that voice recognition cannot be used, even though this Windows account has a zh-CN recognizer, microphone permission, and capture endpoints. The helper collapses recognition failures into a generic message, so the user cannot distinguish an engine failure from an audio-input failure and cannot take a targeted corrective action.

## What Changes

- Preserve the bounded, local-only Windows recognition flow while reporting which recognition stage failed.
- Invoke the PowerShell script through a small launcher boundary so script parse/dispatch failures return a structured, privacy-safe result instead of only process exit code 1.
- Keep stage markers and structured helper errors available to the desktop process when PowerShell exits abnormally.
- Show a safe, actionable message for the failed stage without exposing raw exception details or microphone audio.
- Keep the complete status message visible in the compact chat dock instead of truncating it with an ellipsis.
- Add a Windows-only helper smoke test mode that validates script and speech-engine startup without opening the microphone.
- Encode the localized PowerShell helper as UTF-8 with a BOM so Windows PowerShell 5.1 parses its Chinese strings correctly.

## Capabilities

### New Capabilities
- `desktop-voice-recognition-diagnostics`: Safe, stage-specific reporting for Windows desktop voice-recognition failures.

### Modified Capabilities

## Impact

- `apps/desktop-meguri/src/main/voice-recognition.ps1` and `voice-recognition.mjs`.
- The chat composer error state and the existing desktop voice-input documentation.
- No network services, external speech APIs, or new runtime dependencies.
