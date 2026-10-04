## Why

Meguri currently has no user-facing way to start at Windows sign-in or request administrator-level launch. Adding these options to desktop settings lets the user control both behaviors and have them applied by Windows.

## What Changes

- Add persistent Windows settings for opening Meguri at sign-in and running it with administrator privileges.
- Enable both settings by default for this local desktop setup.
- Apply the selected options through a Windows logon task, with highest privileges when requested, and route manual launches through that task when administrator mode is enabled.

## Capabilities

### New Capabilities

- `windows-desktop-startup`: Configure sign-in startup and administrator-level launch for the native Meguri desktop.

### Modified Capabilities

None.

## Impact

- Extends native desktop preferences, the settings view, and the narrow Electron preload/IPC contract.
- Adds a PowerShell task configuration helper and updates the desktop batch launcher.
- Windows task registration may display a UAC approval prompt when administrator mode is enabled.
