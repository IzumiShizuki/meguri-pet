## 1. Shizuki connection

- [x] 1.1 Add a bounded Shizuki auth and TimePrism client in the Electron main process, with HTTPS origin validation and fixed routes.
- [x] 1.2 Store the rotating refresh token through Electron OS encryption, implement startup refresh and disconnect, and keep all tokens out of renderer responses and logs.
- [x] 1.3 Add narrow preload/IPC contracts for connection status, sign in, sign out, Todo list, create, and complete.
- [x] 1.4 Add a settings section for Shizuki account connection and visible auth/network errors.

## 2. Todo and reminders

- [x] 2.1 Add a Todo panel to the native chat dock with list, create, exact local reminder time, and complete controls.
- [x] 2.2 Implement Shizuki Todo field mapping and preserve existing fields when completing an item.
- [x] 2.3 Add a host-owned reminder poller with at-time and positive-offset handling, a twelve-minute late window, and persistent per-occurrence deduplication.
- [x] 2.4 Deliver reminder events to an in-app card and an Electron system notification where available; stop scheduling after disconnect or quit.

## 3. Voice input

- [x] 3.1 Add a bounded Windows Chinese speech-recognition helper that uses the installed `System.Speech` engine without network upload or audio persistence.
- [x] 3.2 Expose start, cancel, and state events through trusted stage-only IPC and preload methods.
- [x] 3.3 Add a microphone control to the chat composer that inserts recognized text into an editable draft and preserves the draft on cancel/error.

## 4. Verification and handoff

- [x] 4.1 Verify the client, reminder timing/deduplication, and voice state transitions with focused local checks that do not use a production account.
- [x] 4.2 Verify TypeScript and desktop build, then run OpenSpec validation and implementation verification.
- [x] 4.3 Document connection setup, reminders' running-app limit, and the separate Shizuki at-time reminder prerequisite.
