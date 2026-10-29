## 1. Locate and repair the save boundary

- [x] 1.1 Inspect the live failure and reproduce plain versus reactive input through the original Electron preload in an isolated profile.
- [x] 1.2 Send plain settings values and provide accurate save/validation/transport feedback.
- [x] 1.3 Preserve trusted settings IPC checks while making persistence and runtime application testable.
- [x] 1.4 Validate new patches and preserve atomic saves and compatible old configuration readback.

## 2. Regression and delivery

- [x] 2.1 Test successful saves, invalid inputs, failed replacement, legacy readback, consecutive saves and active-state/probe behavior.
- [x] 2.2 Run isolated real Electron preload/settings regression, including reactive form input and untrusted window/origin rejection.
- [x] 2.3 Run desktop tests, typecheck, build, diff checks and OpenSpec validation.
- [ ] 2.4 Verify whether the user's desktop loads the fix and saves successfully without restarting Core/Harness; record any UAC/UI blocker honestly.
- [x] 2.5 Record evidence and create an independent local commit without pushing or staging unrelated changes.

Task 2.4 is blocked on live UI interaction: the existing settings window reports a disabled accessibility pane, prior input produced no observable change, and the latest capture is occluded by another app. The original desktop main has not been restarted, and production configuration has not been written by this repair task. The renderer build is ready; refreshing the focused settings page can load the serialization fix without restarting Core. Complete live verification before archiving.
