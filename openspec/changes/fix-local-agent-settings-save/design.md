## Context

The current renderer sends `agentDraft.value`, a Vue reactive Proxy, through the original Electron context bridge. An isolated Electron 43 reproduction showed a plain object reaches the save handler while the Proxy throws `An object could not be cloned` before the handler runs. The renderer catch incorrectly attributes every exception to the configuration directory. Production logs contain no local-agent persistence error. The live settings window is observable but automation input has not changed its page.

## Goals / Non-Goals

**Goals:** Plain form data at the renderer boundary; explicit save feedback; strict incoming field validation; atomic persistence and compatible readback; tests for the actual preload/IPC path, trusted-origin rejection, runtime updates and read-only probes.

**Non-Goals:** Changing authentication, OS permissions, UAC, startup settings, executor version discovery, task execution features or deployment of Core. No direct production configuration writes as a UI workaround.

## Decisions

- Construct a small plain settings payload at the renderer call site. Do not rely on `structuredClone` of reactive values, JSON round-trips of arbitrary objects, or relaxed Electron serialization/security settings.
- Preserve the settings-window identity and expected URL check. Factor only the existing narrow settings handlers into a testable registration module so isolated Electron tests can exercise the real save path with an explicit test-only trusted window.
- Validate incoming patches separately from tolerant reading of compatible older documents. Reject invalid paths/field types with readable messages. Keep configuration in the existing userData location.
- Use unique temporary files and atomic rename; cleanup must not convert a committed save into a reported failure. Failed validation/persistence does not call runtime update. If runtime application unexpectedly fails after a disk commit, report that distinction honestly.
- Keep save feedback separate from executor probes. An explicit connection test remains read-only and does not become an implicit model task.
- Tests use temporary profiles and fake executor probes. Live checks reuse existing authorized accounts/hosts only after ordinary UI save; no credentials enter fixtures.

## Risks / Trade-offs

- Stricter patch validation rejects values previously silently coerced → compatible stored configuration is still normalized on read; only invalid new input is rejected.
- Filesystem and runtime application cannot form one OS transaction → commit files atomically, apply only committed values, and distinguish an unexpected runtime application failure from a failed disk save.
- User desktop runs with startup/elevation preferences → build and isolated tests are safe; any necessary desktop restart must stop at UAC and keep Core/Harness untouched. Live UI success is not inferred from test fixtures.

## Migration Plan

No schema migration or dependency installation. Build the desktop renderer, run targeted/unit/preload checks, and perform a desktop-only verification when no active requests remain. Preserve source and an independent local commit if runtime verification is blocked. Revert only this commit to roll back code; do not replace user configuration.
