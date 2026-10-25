# Meguri Desktop

`desktop-meguri` is the first-party Electron and Vue frontend for Meguri. It is
being developed beside the current AIRI Stage Tamagotchi fork so behavior can
move incrementally without copying AIRI's monorepo or blocking the existing
desktop launcher.

## Architecture

- `src/main`: startup readiness, transparent stage window, persisted bounds,
  safe generated-file operations, and global visibility shortcut.
- `src/preload`: the narrow renderer bridge. It exposes no Node.js, generic IPC,
  filesystem, or shell surface.
- `src/renderer`: Meguri-owned Vue stage, conversation, character presentation,
  and artifact UI.
- `apps/desktop-airi/src/web-server.mjs`: the shared migration gateway. It keeps
  Core credentials and local paths outside both renderers and accepts the AIRI
  and native Meguri desktop identities.

The native launcher uses loopback port `15173` for this gateway so it does not
silently attach to another Vite application on the common `5173` development
port. The AIRI fallback keeps its existing port and launcher behavior.

## Commands

Install from the repository root, then build and start:

```powershell
D:\environment\nodejs\global\pnpm.cmd install
D:\environment\nodejs\global\pnpm.cmd build:desktop
D:\program\meguri-pet\start-meguri-desktop.bat
```

The launcher starts Electron independently and exits after launching it.
`start-meguri-desktop.bat` reads the main-process source from this checkout and
loads the renderer from `dist/renderer`; it does not run a build. After changing
renderer code, run `build:desktop`, fully quit Meguri, then launch this bat again.
An already running instance keeps its loaded code. `start-meguri-airi.bat`
starts the separate AIRI frontend and does not include native reminder changes.
Launcher errors are appended to
`D:\program\meguri-pet\log\desktop-launcher.log`; Core, TTS, and Electron output
is written to `desktop-core.log`, `desktop-core.err.log`, `desktop-gateway.log`,
`desktop-gateway.err.log`, `desktop-electron.log`, `desktop-electron.err.log`,
`meguri-tts-bridge.log`, `meguri-tts-bridge.err.log`, and
`gpt-sovits-api-v2.log` in the same directory.

## Windows startup and permissions

The **启动与权限** settings default to enabled for both Windows sign-in startup
and administrator launch. Meguri registers a current-user Task Scheduler entry
named `Meguri Desktop`; the sign-in trigger follows the first setting and the
task's highest run level follows the second. Windows asks for UAC approval the
first time the administrator task is registered or changed. Task Scheduler
remembers that approval, so routine sign-in launches do not ask again. The
desktop launcher runs the task when administrator launch is enabled.

Startup task setup errors are recorded in
`D:\program\meguri-pet\log\desktop-startup-settings.log`; they also appear in
the Settings view. Launcher and task bootstrap errors remain in
`D:\program\meguri-pet\log\desktop-launcher.log`.

For renderer development with Electron reloads performed manually:

```powershell
D:\environment\nodejs\global\pnpm.cmd dev:desktop
```

## Windows notification-area entry

While native Meguri runs, a teal **M** icon remains in the notification area,
including when the pet is hidden. Look in the bottom-right **显示隐藏的图标**
(`^`) panel if Windows puts it in overflow. Windows controls this placement;
you can drag the icon onto the taskbar to keep it visible.

- Click or double-click: restore and center the pet on its current screen,
  then bring it forward. Repeated clicks keep it visible.
- Right-click: **显示桌宠（居中）**, **打开聊天**, **设置**, or **退出 Meguri**.
- Hover/menu status: **正在启动**, **服务未就绪**, **正在运行**, or
  **桌宠已隐藏**. These report startup/visibility state, not a continuous
  connection check or proof of reminder/message delivery.

If required startup services are unavailable, clicking reveals the startup
status window. Closing all windows keeps the tray available to reopen the
desktop; use **退出 Meguri** to stop the app and remove its tray icon. Explicit
exit also stops desktop reminder polling. Relaunching the native bat focuses
the existing owner and creates no duplicate tray/runtime. A fully exited app
has no active icon (Windows may retain an old visual until its shell refreshes).

Tray errors are recorded in `log/desktop-electron.log` or
`log/desktop-electron.err.log`. `pnpm --filter @meguri/desktop-meguri test:tray`
checks real Electron tray registration and hidden/closed-window recovery in
an isolated test profile; it sends no notification or external message.

## Current parity

Implemented in the native frontend:

- dedicated startup and capability readiness window;
- transparent, always-on-top, persisted pet window;
- AIRI-style click-through outside controls, chat and drag regions;
- AIRI-familiar character stage, floating controls and chat dock;
- canonical hello, turn, SSE reconnect/snapshot, cancellation and retry;
- PNG expression, intensity, outfit and motion presentation;
- optional local GPT-SoVITS playback;
- managed Live2D imports with pointer/touch interaction and PNG recovery,
  reusing the installed AIRI/Cubism renderer;
- host-authorized GPT-SoVITS resource management, speed/volume and separate
  desktop/Galgame speech controls;
- consented authoritative memory management and bounded session summaries;
- safe Markdown/math replies, source copying, chat wallpaper and palettes;
- first-run data choices, sanitized diagnostics and verified release checks;
- generated-file history entries, file icons and up to three bubbles;
- safe default-application opening through an allow-listed host boundary;
- confirm-gated task forwarding to a locally installed Codex or DeepSeek
  Harness. See [local work agents](../../docs/local-work-agents.md).

Resource formats, AIRI dependency, local service restart requirements and
isolated verification are documented in [native companion resources](../../docs/native-companion-resources.md).
Formal memory requires an explicitly configured authoritative database and
bridge; its configuration and original opt-out are documented in
[native desktop memory](../../docs/native-desktop-memory.md). Browser history
is never reported as formal memory. The completion evidence and remaining
runtime prerequisites are in [the feedback report](../../docs/native-companion-feedback-2026-10-05.md).

## PixPin screenshots

Meguri can start a one-time PixPin region capture from the chat dock:

- **截图问 Meguri** adds the selected image to the composer and preserves the
  existing draft and attachments.
- **截图直接发送** sends the image with the visible question
  “请看看这张截图，告诉我你注意到什么。” A vision-capable model route must
  be configured; if that route fails, the conversation keeps the image and
  question available through its retry action.
- After starting a capture in PixPin itself, press **Ctrl+Alt+Shift+P** in
  Meguri to import the current clipboard image. Pasting with **Ctrl+V** still
  works.
- If PixPin's screenshot window covers Meguri, press Meguri's visibility
  shortcut (default **Ctrl+Shift+M**) once to bring the pet forward; press it
  again while the pet is focused to hide it.

On first use, Meguri looks for PixPin in its Windows uninstall entry and common
install folders. If it cannot find `PixPin.exe`, choose the executable in the
file picker. Download PixPin from the [official PixPin site](https://pixpin.cn/).
Meguri starts PixPin if needed, hides its windows while the selection is active,
then restores them when the capture ends. Its short-lived Windows PowerShell
listener checks clipboard sequence and owner only during that capture, accepts
an image only after a new generation owned by a PixPin process, and never
clears the clipboard. It times out after two minutes; press Esc in PixPin to
cancel its selection, then use the Meguri clipboard shortcut to return to the
chat.

The supported clipboard mode is standard image data. If Meguri reports that
PixPin copied the image as a file, open PixPin **配置 → 系统** and set
**复制图像为文件** to **禁用**. Meguri does not read PixPin cache paths. Large
screenshots over 5 MB are rejected; select a smaller region. The screenshot
actions do not enable continuous screen observation or Glance.

If **Ctrl+Alt+Shift+P** conflicts with another application, Meguri displays the
conflict and the existing **Ctrl+V** paste path remains available.

## Shizuki TimePrism Todo and reminders

Open **设置 → Shizuki 待办**, enter the Shizuki HTTPS site address, account email,
and password, then choose **连接 Shizuki**. The default site is
`https://site.shizuki.online`. The desktop stores an encrypted refresh token in
Electron user data; the password is used only for login. The current connection
flow requires an account password and does not import a browser or QQ session.

Type a request such as **明天上午 9 点提醒我开会** or **今晚 8 点提醒我给小王回电话**
in the chat dock and press send. Meguri opens a review showing the title,
reminder behavior, exact date/time, and **Asia/Shanghai (UTC+08:00)**. Choose
**确认创建提醒** to write it to the connected TimePrism account. Spoken text goes
into the editable draft and uses this same review path when sent. It never
creates a Todo automatically. The parser supports today/tomorrow/day-after,
explicit year-month-day, Chinese hour/minute numerals and minute/hour offsets.
Relative offsets round up to the next minute; the review displays that time.
Bare hours such as **明天 3 点**, missing fields, past/invalid dates, approximate
times and other timezones open a clarification form. Relative dates are frozen
at review; a time that passes before confirmation must be reviewed again.

Use **☑** to list, create, complete, edit and cancel a selected Todo's reminder.
Manual **确认存入 Shizuki** also shows the intended title/time. Modification and
cancellation require selecting a particular Todo and confirming the review;
**取消提醒** disables the reminder and retains the Todo. A failed edit is
unconfirmed: refresh and inspect the same record before retrying. Natural
language edits/cancellations direct you to this panel to select the target.

Creation succeeds only when the server returns an ID and the reviewed title,
time and enabled **0 MINUTE** reminder fields. If the site drops these fields,
Meguri shows an error with the available Todo ID instead of claiming a reminder
was created. Check that record in TimePrism rather than creating another copy.
Definite server rejections permit correction/retry. Timeouts, lost responses
and server errors keep the draft and request ID; **重试 / 核对写入结果** searches
the original record instead of blindly repeating POST. If its existence cannot
be established, it remains unconfirmed. **稍后核对** leaves the server record
untouched. Unconfirmed writes can be restored after restart or by resending the
same title/time. The additive `shizuki-session.json.requests.json` file in
Electron user data stores account-bound request receipts, without credentials.
Its identifiable `meguri-request:<UUID>` Todo detail is used for reconciliation;
editing/removing it can prevent recovery. Preserve the journal when diagnosing
a failed write; deleting it removes local duplicate-write protection.

**取消核对** closes the current review flow. Before dispatch it prevents the
write; after dispatch it waits for the current request to return, then preserves
any unresolved request in the pending list for later reconciliation. It does
not delete or cancel a Todo that TimePrism may already have created.

TimePrism stores Todo data; **Meguri's running desktop process** synchronizes
about every 75 seconds and scans for due reminders every 30 seconds. It shows
an in-app card plus a Windows notification when available. All Todo wall times
are interpreted in Asia/Shanghai independently of the computer timezone; other
zones/DST schedules are unsupported. A failed synchronization clears stale
records, and records older than 105 seconds are suppressed until refreshed.
This avoids firing known-stale cancellations during outages, but cannot
guarantee timing or discover remote edits during network partition. On restart
with a successful sync, it can deliver a missed reminder once within twelve
minutes of its time. Older tasks remain in the Todo list without a late alert.
Occurrence IDs persist across restarts; corrupt/unwritable deduplication storage
stops triggering and shows an error in Shizuki settings. Local issuance does
not prove the user saw a notification. A process crash between recording the
occurrence and presenting it can lose presentation; repeated successful scans
do not reissue it. Transient delivery callbacks retry within the running process;
already displayed local cards are suppressed while the existing QQ outbox retries.
Only one desktop instance owns reminder polling for a user-data profile. The
ownership check runs after the existing Windows administrator handoff; opening
another instance focuses the existing desktop instead of starting another writer.
Choose **设为主账号** in Shizuki settings to bind one Shizuki user ID. Due reminders
for that account also invoke the existing local GPT-SoVITS speech setting. The
desktop queues those occurrences for QQ delivery through remote Core and
AstrBot; changing the connected Shizuki account does not redirect its Todo
titles to the owner's QQ chat. Settings shows the local upload configuration,
pending count, and latest upload error. Core receipt does not confirm QQ send.

The desktop reads `MEGURI_REMINDER_CORE_TOKEN_FILE` (default
`D:\environment\secrets\meguri\desktop-core-token.txt`) in Electron main only.
`MEGURI_REMINDER_CORE_URL` defaults to `https://bot.shizuki.online/meguri-core`;
`MEGURI_REMINDER_CORE_TENANT_ID` defaults to `meguri-staging`. Core and AstrBot
must be deployed and configured separately. No new reminder triggers while the
desktop process is stopped or the computer is off. Always-on behavior requires
a **server-side persistent scheduler/worker**, which this desktop feature does
not provide. Core's event inbox is not such a scheduler, and Core acceptance
does not mean AstrBot delivered a QQ message. Speech/OS notification failure
does not remove the local reminder card. Existing owner speech/QQ settings
remain separate from creation confirmation.

The only scheduling capability currently allowed is **remind me**. A request
such as **明天下午 3 点帮我检查订单** is rejected as an unsupported scheduled
action. Order checks, shell commands, computer control, external messages,
recurring jobs and offline-guaranteed reminders are not arranged. A reminder
whose title says “检查订单” only notifies you; it does not inspect an order.

The companion Shizuki change `support-timeprism-at-time-reminders` is required
for zero-minute Todo support. Read-only probes on **2026-10-04** found the public
site reachable and the Todo API requiring login; the public documentation URL
returned frontend HTML and the Core reminder path required authentication.
These results establish neither deployed zero-minute persistence nor offline
scheduling or QQ delivery. Production end-to-end remains **unverified**. Mock,
loopback HTTP, rendered UI and preload tests are recorded separately in
`openspec/changes/add-natural-language-reminders/verification.md`. No production
test reminders or external messages were created.

## Local voice input

The **🎙** button in the native chat dock starts a long, turn-based voice call
using the installed Windows `System.Speech` recognizer. Meguri sends each
completed Chinese utterance through the existing conversation flow and listens
again after the reply. Press **■** or click the microphone button again to hang
up. Recognition pauses while Meguri generates or speaks a reply, so this mode
does not support talking over Meguri; enabled local GPT-SoVITS playback follows
the existing speech settings. If reply speech is disabled or unavailable, the
text reply remains visible and listening resumes.

Microphone audio is processed locally and is not saved or uploaded. The
recognized transcript is sent under the existing AI-chat consent and privacy
settings. Windows microphone permission and a `zh-CN` speech recognizer must be
available. A silence cycle automatically rearms recognition; a recognition or
conversation error is shown and ends the call.

Meguri identifies whether a failure happened while opening the default microphone, starting or running the Chinese speech engine, or running the local helper. For an audio-input error, check the Windows default recording device and **Settings > Privacy & security > Microphone** desktop-app access. For a speech-engine error, check the installed Chinese speech feature; for a helper-process error, restart Meguri. These messages use fixed safe text and do not include raw exceptions or recorded audio.

See [voice-long-call-research-2026-10-07.md](../../docs/voice-long-call-research-2026-10-07.md)
for open-source references to AIRI, Pipecat, LiveKit Agents, and sherpa-onnx.

See the [PixPin script reference](https://pixpin.cn/docs/configuration/script)
and [system settings](https://pixpin.cn/docs/configuration/system).

## Local work agents

`src/main/local-agents/` and `src/main/local-agent-runtime.mjs` connect Meguri to
work agents the user installed and signed into separately. The renderer only
gets a narrow bridge: freeze a card, confirm one exact version, read receipts.
There is deliberately no general-purpose send member.

- `receipts.mjs` is the durable "send at most once" boundary: the claim is
  committed before the irreversible write.
- `codex-bridge.mjs` speaks the official `codex app-server --stdio` JSONL RPC and
  declines every approval request.
- `dsh-bridge.mjs` speaks the local Harness Host RPC over loopback and folds its
  event journal into a receipt; ambiguity always yields `unknown`.
- `dsh-preset.mjs` installs the byte-exact `meguri-work-v1` preset and never
  overwrites a file that already exists.

The capability is off by default, and a missing work agent never affects chat.

Still provided by the AIRI fallback while later migration slices are built:

- attachment and `@` resource-selection UI;
- document-edit confirmation and daily briefing controls;
- microphone/VAD and detailed audio settings from the AIRI fallback;
- the full settings catalog, tray menu and updater;
- Live2D, VRM, Spine, Godot, plugins and advanced renderer configuration.

Next migration order is attachments/documents, briefings/settings,
window snapping/attachment, then an optional advanced character renderer. The
existing `start-meguri-airi.bat` remains the fallback throughout migration.

### Desktop reply and tool bubbles

Collapsed chat shows the latest Meguri reply in a small speech bubble above a
compact composer. Select **展开聊天** or the expand-records button to see the
complete conversation. Incoming replies and errors keep history collapsed.
Todo, reminder review, and attachment selection open a larger functional
bubble without revealing history; closing them restores compact chat unless history remains
explicitly expanded. Drafts stay in the same mounted composer across these
changes.

Use **▷ 重放语音** in the latest-reply bubble or beside a completed Meguri
reply in chat or Galgame history to listen to that reply again. The button
shows synthesis progress and changes to **■ 停止** during playback. Replay uses
the original speech text and style when present; older replies use their text.
It works with automatic speech disabled, preserves the configured volume and
speed, and never sends a new conversation turn. The existing local speech
service must be running; synthesis or audio failures appear beside the reply.
Replay is disabled while a live voice call is active.

The pet uses a fixed 350 by 540 CSS-pixel surface for both PNG and Live2D. The
compact window defaults to 390 by 560 DIP; expanded panels use 800 by 560 DIP,
with the character beside the panel. Narrow windows keep the panel within the
viewport. Existing tall expanded dimensions migrate on the next desktop restart.
Live2D stays loaded when chat preferences change.

After rebuilding, restart Meguri to load the updated native window controller.
Run `pnpm --filter @meguri/desktop-meguri test:visual-bubbles` for the isolated
Electron layout check. Screenshots and results are written to
`.codex-runtime/bubble-layout-smoke/`; the check uses preview todos and a local
Haru fixture when available, with external requests blocked.

Expanded chat places todo, new conversation, speech, more pet controls, and
collapse in one header. The header remains draggable outside its buttons;
Escape closes the controls drawer and returns focus to its toggle. Temporary
panels restore the pet's previous compact placement even near display edges.
Native cursor polling restores hover-hidden portraits when the cursor leaves
the window or a cursor sample fails.

Run `pnpm --filter @meguri/desktop-meguri test:chat-interaction` for the isolated
hover recovery, header, keyboard drawer, and right-edge placement checks.
Screenshots and results are saved in `.codex-runtime/chat-interaction-smoke/`.

### Optional supervised feedback

Open **设置 → 监督与训练** to enable optional feedback on subsequent replies.
Choose a reply or write a behavior correction, then explicitly consent for that
submission. A saved receipt can be withdrawn later, including from earlier sessions.
Training mode is off by default; saving feedback does not train or switch a model.
Feedback recovery requires the updated Core and authenticated desktop gateway.

See [the P0 learning and recovery record](../../docs/p0-learning-and-runtime-recovery-2026-10-08.md)
for offline review/export, deployment prerequisites and test evidence. Run
`pnpm --filter @meguri/desktop-meguri exec electron scripts/training-feedback-smoke.mjs`
for the isolated renderer check with a fake feedback API.

### Local runtime and attention-aware presentation

The supported Windows desktop launcher starts Java Core on loopback port
18080, the desktop gateway on 15173, and local TTS on 9880 when each is not
already listening. Core, the desktop gateway, and the renderer are separate
processes. Local commitment follow-up and desktop delivery therefore run only
while the desktop integration is active; TimePrism reminders remain the
server-backed reminder path.

Unsolicited notices share the companion attention policy and automatic
audio/action effects use a persistent playback receipt before execution.
Already claimed automatic effects are suppressed after reconnect or reload;
use the explicit reply replay control for an intentional new playback.
Preference controls are reversible and do not train the model or edit formal
memory. See the [P2 runtime map](../../docs/p2-runtime-map-2026-10-08.md) for
startup configuration, rollback boundaries, and which checks require a real
desktop/audio/Live2D session.
