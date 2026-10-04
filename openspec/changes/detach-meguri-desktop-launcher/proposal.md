## Why

The native desktop launcher currently runs `pnpm start` in the foreground, which keeps a console open for the entire Electron session. Users should be able to start Meguri from its launcher and interact with the desktop window without keeping a command prompt visible, while still having startup and runtime errors available for diagnosis.

## What Changes

- Launch the native Electron desktop as an independent process so the startup console can exit after initialization.
- Capture launcher failures and Electron standard error in dedicated files under the existing Meguri log directory.

## Capabilities

### New Capabilities

- `meguri-desktop-launcher`: Start the native desktop without a persistent console and preserve diagnostic errors in local log files.

### Modified Capabilities

None.

## Impact

- Updates `start-meguri-desktop.bat` and `ops/scripts/start-meguri-desktop.ps1`.
- Writes local launcher and runtime logs under the repository's `log/` directory.
- Reuses the installed Electron executable and existing Core, gateway, and TTS startup flow.
