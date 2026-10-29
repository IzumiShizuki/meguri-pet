## Why

Saving Work Agent settings currently reports a generic directory error and prevents the user from enabling the existing Codex/Harness integration. The failure must be located across the renderer, preload, trusted IPC handler and persistence boundary instead of bypassing that boundary.

## What Changes

- Make ordinary reactive form values cross the Electron boundary as validated plain data.
- Return actionable validation/save feedback without mislabeling transport failures as filesystem permissions.
- Preserve previous settings on failed saves, read compatible old configuration, and update the running application only from committed settings.
- Add isolated regression coverage through the real preload, configuration persistence and runtime settings path.

## Capabilities

### New Capabilities
- `desktop-local-agent-settings-save`: Reliable, trusted, atomic save/readback and explicit connection testing for work-agent settings.

### Modified Capabilities
None. The existing work-forwarding settings change has not been archived into the main spec tree; this focused delta adds the save boundary contract.

## Impact

Affected: native desktop Work Agent settings renderer, configuration module and narrow IPC handling, plus regression tests. No new dependencies, OS permissions, credentials, model settings, Core/Harness restart, or automatic startup changes. Delivery is a local commit only.
