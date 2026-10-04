## Context

See proposal.md for motivation and the three delta specs for behavior. The native Electron app has a trusted main process and a narrow preload bridge. It currently exposes TTS but no microphone capture. Shizuki already exposes authenticated TimePrism Todo CRUD; its `/api/v1/auth/tokens` issues two-hour access tokens and rotating thirty-day refresh tokens. The site API is available through `https://site.shizuki.online/api/`. A separate Shizuki change adds zero-minute reminder offsets, because current validation accepts only positive offsets.

## Goals / Non-Goals

**Goals:**

- Reuse Shizuki's user-scoped Todo API without creating a parallel task database in Meguri.
- Keep credentials and reminder scheduling in the trusted desktop host, with explicit, bounded IPC methods.
- Provide useful Todo and voice controls even during a Core/model outage.

**Non-Goals:**

- QQ proactive delivery, server push, or reminders while the desktop process is stopped.
- Generic arbitrary HTTP requests, browser-session scraping, sharing one Shizuki login across AstrBot users, or handing tokens to an LLM.
- Free-form LLM intent parsing or automatic submission of recognized speech.

## Decisions

### 1. Connection and transport

The Electron main process owns a `ShizukiTimePrismClient`. Settings sends an email and password once through a dedicated IPC method; the client calls `POST /api/v1/auth/tokens` with `grant_type=EMAIL_PASSWORD`. Only the refresh token is persisted, encrypted using Electron `safeStorage`, under the app's user-data directory. Access tokens stay in memory. The encrypted token file also records the configured origin and nonsecret account identity. If OS encryption is unavailable, connection fails with a visible error instead of storing a plaintext refresh token.

Before an authenticated request, the client refreshes an expired access token. Refresh calls are serialized so parallel Todo and reminder reads cannot rotate the same token twice. A rotated refresh token is written atomically before the client reports success. On a 401, one refresh and one retry are allowed. Disconnect clears local tokens and attempts the site's logout route.

The origin defaults to `https://site.shizuki.online`. The client accepts HTTPS origins and HTTP loopback origins for local development. It builds paths from fixed route constants, rejects redirects when sending credentials or bearer tokens, limits response size and time, and returns sanitized errors to the renderer. This uses the existing site API without coupling to the website's browser localStorage or the Meguri Core token. A browser-login or device-code flow would need new Shizuki auth endpoints; email/password is the available flow for this slice.

### 2. Todo interaction

A Todo panel in the desktop chat dock lists Shizuki todos, accepts title plus optional local date/time, and lets the owner mark a todo done. The panel shows the intended values and requires a deliberate create click. For a scheduled reminder, it sends `due_at` as a local wall-clock timestamp with `time_precision=MINUTE`, `timing_mode=DEADLINE`, `reminder_enabled=true`, `deadline_remind_value=0`, and `deadline_remind_unit=MINUTE`. Unscheduled todos omit reminder fields. Completion updates the current Todo using the site's full upsert shape, preserving fields not edited in Meguri. The panel displays the site's returned record and never treats a timeout as confirmed success.

The renderer cannot submit an arbitrary path or method. The preload exposes only `status`, `connect`, `disconnect`, `listTodos`, `createTodo`, and `completeTodo`. Main validates length, date, account state, and item ID. A direct UI path is chosen because model inference is currently failing, and direct actions can be independently verified.

### 3. Reminder lifecycle

Main owns a bounded reminder loop while the app runs. It refreshes the Todo snapshot from Shizuki about every 75 seconds and scans the current snapshot about every 30 seconds. The trigger for the new at-time offset is the Todo due time; other supported positive minute/day offsets are subtracted from it. Local TimePrism timestamps are parsed in the desktop's local timezone, matching the user's chosen wall-clock time. Completed or disabled todos do not trigger. The loop sends a sanitized reminder event to the stage renderer and attempts an Electron system notification. The renderer shows an in-app card, including when the OS notification is unavailable.

The deduplication key includes Shizuki user ID, Todo ID, due time, and offset. It is persisted in user data, with old keys pruned after seven days. Events at most twelve minutes late are shown once after restart; older events are skipped. The timer is stopped on disconnect or app quit. A brief outage uses the last successful snapshot but labels connection failures in the Todo panel; updates made elsewhere may be delayed until the next successful sync. Notifications are local; no QQ message is sent.

### 4. Voice input

The existing Windows zh-CN `System.Speech` recognizer is launched through a fixed local PowerShell helper script. Main starts at most one recognition process for the trusted stage renderer, with a fixed language, timeout, and bounded UTF-8 JSON output. Stop/cancel terminates listening. This avoids a new network ASR dependency at a time when the model endpoint has a TLS failure; browser Web Speech in Electron is not a dependable offline fallback.

The preload exposes `voice.start`, `voice.cancel`, and `voice.onChanged`. The chat dock has a microphone control and a listening state. A successful transcript is inserted into the editable draft while preserving existing text; it is never sent or converted to a Todo automatically. Audio remains in the Windows recognizer process and is not saved by Meguri.

## Risks / Trade-offs

- [The owner uses only OAuth login] → The initial connection UI supports email/password; a site device authorization flow is a follow-up if needed. This limitation is shown in settings.
- [Refresh rotation completes remotely but local persistence fails] → Do not claim the operation succeeded; clear unusable local state and ask for a fresh login.
- [Meguri is stopped at due time] → Only reminders within the twelve-minute late-start window are displayed on restart; UI describes this limit and startup-at-login remains available.
- [Windows microphone or recognizer is unavailable] → Preserve the draft, surface a specific error, and leave keyboard entry usable.
- [Site or model networking fails] → Todo errors are independent and visible; no optimistic saved status is shown. The model outage does not block direct Todo or voice controls.

## Migration Plan

1. Implement and verify the companion Shizuki at-time offset change before using zero-minute reminders against production.
2. Add desktop connection, Todo, reminder, and voice modules behind their explicit UI controls. Existing preferences and sessions remain readable.
3. Launch the desktop app, connect the owner's Shizuki account through settings, and create a reviewed reminder. No live account write is performed by automated verification.
4. Roll back the desktop build if needed; disconnect removes the encrypted local token. Todos already saved in Shizuki remain there and can be managed in TimePrism.
