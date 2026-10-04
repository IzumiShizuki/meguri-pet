## Context

The desktop stores preferences in the Electron main process and exposes them through a narrow preload bridge. The batch launcher starts the Core, gateway, TTS bridge, and Electron UI. Windows supports user logon tasks with limited or highest run levels, which can also be invoked manually.

## Goals / Non-Goals

**Goals:**
- Add two persistent, initially enabled options to the existing desktop settings view.
- Apply sign-in and administrator choices through one Windows task with a logon trigger when requested.
- Keep the manual batch launcher consistent with the saved administrator choice.
- Report UAC cancellation or task-registration failures to the settings view and launcher logs.

**Non-Goals:**
- Change privileges of Core, gateway, or TTS processes independently from the desktop process.
- Add machine-wide startup for other Windows accounts.
- Require administrator privileges when both options are disabled.

## Decisions

- Persist `openAtLogin` and `runAsAdministrator` under desktop preferences. Both default to `true` for this local setup, as requested.
- Use a single Task Scheduler entry named `Meguri Desktop`. It has an interactive logon trigger only when startup is enabled and uses the highest run level only when administrator launch is enabled. With the trigger disabled, the task remains available for manual elevated launches.
- Use a dedicated PowerShell helper for task registration. It self-elevates through Windows UAC only when the existing task must be created or changed to the highest run level, writes the applied flags to the current user's registry key, and records errors under the repository `log/` directory.
- Keep renderer access narrow: the settings view sends only the two booleans over a dedicated startup IPC method. The Electron main process calls the helper with fixed paths and validated booleans.
- The batch launcher checks the applied administrator flag. If enabled, it runs the registered task and exits; otherwise it starts the normal desktop launcher.
- Synchronize saved settings when Meguri starts. If Windows refuses the requested configuration, keep the desktop usable and show the failure in startup settings.

## Risks / Trade-offs

- [Risk] Windows may reject a highest-run-level task or the user may cancel UAC. → Keep the previous preferences and task unchanged, log the failure, and show it in the settings view.
- [Risk] Elevated UI code has access to more of the user's system. → Make the setting explicit, separate from sign-in startup, and explain the effect beside the checkbox.
