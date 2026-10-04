## Why

Meguri's native desktop app cannot create or complete the owner's Shizuki TimePrism todos, and it has no microphone input. A typed request such as "12 点提醒我睡觉" currently depends on a failing model call and does not create a durable reminder. The owner needs a direct, verifiable path from Meguri to TimePrism and an editable voice transcript.

## What Changes

- Add an explicit Shizuki account connection to the native desktop app, with encrypted refresh-token storage, automatic token rotation, and visible connection errors.
- Add a Todo surface for listing, creating, and completing TimePrism todos. A scheduled todo records its exact local date and time and reminder setting in Shizuki; the user sees the final details before a write.
- Deliver due reminders from the connected TimePrism data through Meguri desktop notifications while the app runs, with persistent deduplication and an understandable missed-reminder window.
- Add push-to-talk Chinese speech recognition using the Windows recognizer already installed on the owner's machine. Put recognized text into the editable message draft without automatically sending it or creating a todo.
- Keep task and voice actions usable when the LLM provider is unavailable, and report site authentication, network, and microphone failures directly.

## Capabilities

### New Capabilities

- `shizuki-timeprism-connection`: Connect a Shizuki user account and call the existing user-scoped TimePrism API without exposing credentials to the renderer or model.
- `desktop-todos-reminders`: Manage Shizuki todos from Meguri and display due reminders while the desktop app is running.
- `desktop-voice-input`: Recognize a short spoken utterance locally and place its transcript in the chat draft for review.

### Modified Capabilities

None.

## Impact

- Native Electron main process, preload bridge, settings, chat dock, and new Todo interface in `apps/desktop-meguri`.
- Existing Shizuki `/api/v1/auth/tokens` and `/api/v1/light-apps/todos` APIs at the configured HTTPS site origin; no existing browser session or Meguri Core token is reused.
- A separate Shizuki-site OpenSpec change enables zero-minute "at time" reminder offsets in TimePrism.
- Reminder delivery requires the desktop process to be running. Production account connection and real reminder writes require the owner's Shizuki login.
