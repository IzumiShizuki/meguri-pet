## Context

See `proposal.md` for the observed failure. The current IPC handler in `apps/desktop-meguri/src/main/index.mjs` calls the Windows task configurator before `writeDesktopState`. The state file is under Electron's per-user `userData` directory; it is not the Task Scheduler registry entry or an administrator-only location. The state file is currently written as one direct JSON write. The checked-in startup helper requests elevation when an existing task is at `Highest`, including when changing it to `Limited` or removing it.

The current main checkout has no `Meguri Desktop` scheduled task or startup registry flags, and its local startup-settings log has no recent failure. Therefore, the task downgrade/UAC sequence is a confirmed code path but not yet a confirmed explanation for this specific user's attempt.

## Goals / Non-Goals

**Goals:**

- Persist the user's requested per-user preference independently of Task Scheduler success.
- Make the IPC response distinguish an unsaved local preference, a saved preference with a pending Windows update, and a fully applied update.
- Keep the requested checkbox value visible after a Windows task failure, expose a retry, and warn that the old task remains effective until Windows accepts the change.
- Keep the existing task privilege, logon-trigger, account, and UAC rules intact.

**Non-Goals:**

- Change Windows ACLs, task principals, UAC policy, or execution policy.
- Claim that administrator launch has been disabled when the scheduled task update was cancelled.
- Modify unrelated settings persistence or migrate the desktop state file.

## Decisions

1. **Write user preferences before invoking Task Scheduler.** The main process computes the merged preference and writes it to the current user's state file first. A local write failure returns the previous preference and never invokes the task helper. This keeps an operating-system permission prompt from discarding a choice that the user profile can save.

2. **Keep task application as a separate result.** The IPC response retains `ok` as “preference and Windows task both applied,” adds `saved` for local persistence, and reports `status.pending` when the preference was saved but Windows rejected or could not apply the task. The settings view adopts the saved choice, shows an error/pending state, and leaves the retry action available. Existing callers that receive an older response can infer `saved` from `ok`.

3. **Do not work around a denied task update.** The task remains exactly as it was when UAC is cancelled or Task Scheduler rejects the operation. The UI states this clearly. Retry uses the already-saved desired values and the existing supported helper. No registry-only shortcut or task permission change is added.

4. **Explain downgrade approval in the settings copy.** If a registered task is already at highest privilege, disabling or changing it can require one UAC approval. This is shown before saving, and cancellation is described as a pending task update rather than a lost local preference.

5. **Use an injected workflow for deterministic tests.** A small main-process workflow accepts persistence and task-application functions so tests can reproduce write failure, task failure/cancellation, successful retry, and successful application without editing the real user profile or Windows Task Scheduler.

## Risks / Trade-offs

- [Risk] A saved desired setting can temporarily differ from a still-registered Windows task after UAC cancellation → Keep task status visibly pending, state that the previous task remains effective, and keep retry enabled.
- [Risk] A future app launch reconciles the saved preference and may present UAC again while the task is stale → Preserve the existing startup reconciliation behavior; the user can accept the one-time update or keep the visible pending state.
- [Risk] Direct JSON writes can still fail or be interrupted → Treat a reported local write error as unsaved and do not attempt the OS task update. Atomic writes are a separate change because no evidence ties them to this failure.

## Migration Plan

No state-file schema change is required. Existing installations keep their saved startup values. Build and validate offline; record the old JAR and desktop renderer hashes; then use the documented local launch/deploy scripts. If deployment fails, restore only the recorded application artifacts and verify the prior health state. A UAC prompt is handled by the user; cancellation leaves the old task in place.
