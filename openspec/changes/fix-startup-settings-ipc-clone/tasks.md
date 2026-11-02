## 1. Reproduce

- [x] 1.1 Run the production save handler with Vue refs and production preload at a structured-clone boundary; demonstrate the reported failure and duplicate feedback.

## 2. Fix

- [x] 2.1 Send an explicit two-boolean DTO and deduplicate identical feedback while retaining retry and pending-save behavior.

## 3. Verify

- [x] 3.1 Cover all boolean combinations, bridge rejection, saved/pending status, retry, and distinct messages in focused regressions.
- [x] 3.2 Verify the real contextBridge/IPC boundary in isolated Electron, run desktop tests and typecheck/build, and record limits without applying Windows settings.
