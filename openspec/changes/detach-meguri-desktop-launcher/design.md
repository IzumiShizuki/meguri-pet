## Context

The launcher currently starts Core, the gateway, and TTS in background processes, then blocks on `pnpm start` for the Electron UI. The package already has a local Electron executable. All logs created by the native desktop startup flow belong under the repository-root `log/` directory.

## Goals / Non-Goals

**Goals:**
- Return from the batch and PowerShell launchers after starting the Electron UI.
- Preserve startup errors and Electron diagnostics in the existing local log directory.

**Non-Goals:**
- Change Core, gateway, TTS, or renderer readiness behavior.
- Package the desktop as an installer or change its Electron security model.

## Decisions

- Keep the existing batch entrypoint, but redirect PowerShell output and errors to an append-only `log/desktop-launcher.log` and remove the failure pause. This lets the command window close after either a successful handoff or a logged startup failure.
- Start the installed `electron.exe` directly with the same `src/main/index.mjs` entrypoint used by the package script. `Start-Process` returns immediately, while Electron stdout and stderr go to dedicated desktop log files.
- Redirect the background Core process to its own stdout and stderr logs because its PowerShell window is hidden and otherwise its startup errors would be lost. Pass the same repository-local log directory to the desktop-launched TTS bridge and its upstream process.
- Preserve gateway and TTS logging as currently configured.

## Risks / Trade-offs

- A missing or broken Electron executable means no UI appears; the batch and launcher logs retain the startup error for diagnosis.
- Electron output logs are replaced at the start of a new launch so the current session's error is easy to find; the launcher log keeps appended startup history.
