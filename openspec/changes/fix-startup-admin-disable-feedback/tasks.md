## 1. Persist and apply separately

- [x] 1.1 Add a testable workflow that writes the merged per-user startup preference before calling the Windows task configurator, returning whether the preference was saved and whether the OS task is applied.
- [x] 1.2 Update the main-process IPC and shared response type so a task failure preserves the saved choice with a pending status, while a local write failure leaves the prior choice and skips task application.

## 2. Explain and recover in Settings

- [x] 2.1 Keep saved choices visible after a task failure, show the actual Windows task status, and leave retry enabled.
- [x] 2.2 Explain when turning off administrator startup requires UAC and that cancellation leaves the previous Windows task effective.

## 3. Verify and deploy

- [x] 3.1 Add isolated regressions for successful apply, UAC/task cancellation, retry, and local write failure; run focused desktop tests and typecheck/build.
- [x] 3.2 Back up the active artifacts, load the authorized build through the supported deployment flow, and verify health and the effective startup settings without changing them silently. See `../fix-startup-settings-ipc-clone/verification.md` for the 2026-11-02 authorized restart evidence.
