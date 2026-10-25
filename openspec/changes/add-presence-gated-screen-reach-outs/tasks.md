## 1. Reproducible main-branch baseline

- [x] 1.1 Commit the existing tested desktop/Core dependency set on master, preserving unrelated dirty files and excluding credentials.

## 2. Native presence and observation

- [x] 2.1 Add trusted native activity/lock/suspend state with a strict 600-second expiry and narrow IPC.
- [x] 2.2 Add bounded foreground-window in-memory Windows OCR with before/after guards, no files/clipboard and graceful unavailable handling.
- [x] 2.3 Verify activity boundary, resume/lock, unknown state, OCR exclusion/failure and cancelled results.

## 3. Proactive flow and controls

- [x] 3.1 Gate the existing scheduler before generation and delivery without resetting chat idle on global input.
- [x] 3.2 Add fresh bounded untrusted screen context to the tool-free Core provider and test identity/freshness/no fake turns.
- [x] 3.3 Add opt-in preferences, live status and explanatory copy; verify existing profile migration and accessible controls.

## 4. Merge and deployment acceptance

- [x] 4.1 Run relevant regression/type/build/native tests and verify requirements/OpenSpec.
- [ ] 4.2 Commit master implementation and merge master into codex/companion-memory-corrections; verify ancestry and unrelated-file fingerprints.
- [ ] 4.3 Back up runtime artifacts/configuration and deploy matching local bridge/Core/gateway/desktop; enable the explicitly authorized screen option.
- [ ] 4.4 Verify deployed health, native activity, isolated OCR/model flow and inactivity suppression; document versions, evidence and rollback.
