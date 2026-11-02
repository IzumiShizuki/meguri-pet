## Why

Saving startup settings currently passes a Vue reactive draft across the desktop bridge and can fail with “An object could not be cloned.” The same failure is rendered twice, obscuring whether Windows applied anything.

## What Changes

- Send a plain DTO containing only the two selected startup booleans from the settings save handler.
- Render identical startup status and feedback messages once, while preserving distinct pending/error information.
- Exercise the real settings handler and preload invocation with a structured-clone boundary and regression tests.

## Capabilities

### New Capabilities
- `startup-settings-transport`: Serializable startup save requests and nonduplicated save feedback.

### Modified Capabilities
None.

## Impact

Desktop SettingsView and focused desktop tests. No Windows preference changes, task registration, elevated execution, deployment, or dependency installation is needed for validation.
