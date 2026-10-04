## Why

The native desktop currently has no notification-area entry. A hidden or misplaced pet can look like an exited app, leaving the user without a reliable way to find it or check whether Meguri is running.

## What Changes

- Keep one recognizable Meguri icon in the Windows notification area while the owning desktop process runs, including when the pet is hidden.
- Show startup, unavailable-service, visible-pet and hidden-pet status in the tooltip and context menu.
- Let a click recover and focus the pet at the current screen center; offer show pet, open chat, settings and explicit quit in the menu.
- Keep the entry usable during startup and remove it on exit. Preserve existing user data and single-instance ownership.
- Document how Windows controls taskbar versus hidden-icon placement and how the native bat loads the latest build.

## Capabilities

### New Capabilities

- `desktop-system-tray`: Native process presence, status and trusted desktop recovery through the notification area.

### Modified Capabilities

None. Existing reminder and shortcut contracts remain unchanged.

## Impact

Native Electron main process, a bundled multi-size icon, desktop tests/smoke checks and user documentation. No new dependency, remote deployment, external messaging, reminder creation or renderer IPC capability is needed. The separate AIRI frontend is outside this change.
