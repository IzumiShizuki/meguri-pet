## 1. Inventory and contract

- [x] 1.1 Inspect native startup, ownership, visibility and shutdown; define tray behavior and acceptance scenarios.

## 2. Native tray and recovery

- [x] 2.1 Add a bundled multi-size icon and a tray controller with status/menu updates, caught actions and idempotent disposal.
- [x] 2.2 Integrate after single-instance ownership; recover hidden/minimized/missing windows, open chat/settings, refresh status and clean up on exit.

## 3. Verification and deployment

- [x] 3.1 Verify status, clicks/menu actions, failures, disposal and real isolated Electron tray/window recovery.
- [x] 3.2 Update user docs; run desktop tests, typecheck, build, strict OpenSpec validation and verify spec correspondence.
- [ ] 3.3 Commit without rewriting history, deploy through the native launcher and verify the new process/tray registration without production writes.
