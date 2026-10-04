## Context

See proposal.md for motivation. The native Electron app has hidden transparent stage windows and trusted settings/chat actions but no Tray resource. `start-meguri-desktop.bat` runs the main source and Vite's built renderer. Windows administrator handoff precedes the profile instance lock. Existing visibility helpers do not restore minimized windows. Startup readiness can fail while the process stays running.

## Goals / Non-Goals

**Goals:** Add a reliable local presence/recovery seam in the main process with real Windows registration verification and focused behavioral tests.

**Non-Goals:** Force Windows overflow placement, modify startup/elevation preferences, extend renderer IPC, change server reminder guarantees, or alter AIRI.

## Decisions

1. Create and retain an Electron Tray after elevation handoff and instance ownership, before readiness checks. Use a checked-in multi-size ICO with an original teal Meguri M mark. Electron recommends ICO on Windows. Keep creation failure isolated so an unavailable shell icon does not stop desktop startup. Alternatives: taskbar presence does not provide recovery for a hidden transparent window; renderer-owned resources would disappear with that window.
2. Put tray lifecycle, status/menu assembly and caught async actions in a small dependency-injected main module. Main supplies current readiness/visibility and trusted reveal/settings/quit callbacks. Refresh from readiness and window show/hide/minimize/restore/close events, rather than introduce another polling loop. Status is local startup/visibility information, not continuous service health.
3. Clicks and second-instance activation share an idempotent reveal action: restore, center on the window's available display, show and focus. Open chat also enables visible/expanded chat through existing preferences. An unavailable startup shows the startup status window. Keep a tray-owning app alive on window-all-closed; recreate a missing stage only after existing readiness succeeds. Explicit quit still cleans reminders, workers and tray.
4. Do not set a GUID for the development Electron executable: Electron recommends GUIDs with signed application executables and associates unsigned identities with their executable path. Windows owns icon pinning/overflow placement; document pinning without changing system settings.
5. Verify actual Tray, ICO and BrowserWindow behavior using an isolated Electron smoke process/profile and a synthetic document, with no production APIs, notifications or messages. Unit checks cover menu state, repeated clicks, error handling and disposal. Native registration/hidden-window recovery is separate from human visual confirmation of Windows overflow placement.

API behavior was checked against the installed Electron runtime and the official [Tray documentation](https://www.electronjs.org/docs/latest/api/tray) and [nativeImage documentation](https://www.electronjs.org/docs/latest/api/native-image).

## Risks / Trade-offs

- [Windows manages pinning] → Explain the taskbar overflow arrow and optional user pinning; do not promise a forced location.
- [Async recovery and shutdown overlap] → Catch callback failures, gate application reveal on shutdown, make disposal idempotent and remove event handlers.
- [Windows may deny stopping an administrator desktop] → Rebuild/commit first, scope any restart to the verified native process, use the configured task for launch, and ask the user to quit only if stopping is actually denied.
- [Cached startup checks are not a live health monitor] → Describe tooltip states as startup/visibility; retain existing detailed status UI.

## Migration Plan

No user-data migration. Run desktop tests, typecheck, build, strict OpenSpec validation and native tray smoke; commit normally. Fully exit the prior desktop, then use the native bat and verify a new stage process and tray registration logs. Roll back via a new revert commit and rebuild/restart if necessary; never reset or rewrite history.
