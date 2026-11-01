## Why

The desktop currently applies the Windows scheduled task before it saves the user's per-user startup preferences. When lowering an existing highest-privilege task requires UAC, cancelling that prompt also discards the selected preference, so the UI appears unable to save it.

## What Changes

- Save valid startup preferences to the normal user state before applying the Windows task.
- Report local preference persistence separately from whether Windows accepted the task update; keep an explicit pending/error state after UAC cancellation or task failure.
- Explain that disabling administrator startup may require one UAC approval when an existing task must be downgraded.
- Add isolated regression coverage for success, cancellation, task failure, retry, and local state write failure.

## Capabilities

### New Capabilities
- `startup-preference-persistence`: Persist the user's desired launch settings independently while accurately reporting Windows task application state.

### Modified Capabilities
- None. The existing Windows startup capability already requires cancelled task changes to remain unapplied and report failure; this change distinguishes saved preference from effective Windows task state.

## Impact

The Meguri desktop main process, startup-settings IPC response, settings view, and focused desktop unit tests. Windows Task Scheduler behavior, account privileges, and UAC policy remain unchanged.
