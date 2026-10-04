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

## Current parity

Implemented in the native frontend:

- dedicated startup and capability readiness window;
- transparent, always-on-top, persisted pet window;
- AIRI-style click-through outside controls, chat and drag regions;
- AIRI-familiar character stage, floating controls and chat dock;
- canonical hello, turn, SSE reconnect/snapshot, cancellation and retry;
- PNG expression, intensity, outfit and motion presentation;
- optional local GPT-SoVITS playback;
- generated-file history entries, file icons and up to three bubbles;
- safe default-application opening through an allow-listed host boundary;
- confirm-gated task forwarding to a locally installed Codex or DeepSeek
  Harness. See [local work agents](../../docs/local-work-agents.md).

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

Use the **☑** button in the native chat dock to list, create, and complete Todo
items in the same TimePrism account. A new item can include a local date and
time for an exact-time reminder. The item is shown as saved only after Shizuki
confirms it. Voice recognition text may be used as a draft title, but creation
always requires a click on **存入 Shizuki**.

Meguri checks reminders while its desktop process is running and shows an
in-app card plus a Windows notification when available. It can deliver a missed
reminder once within twelve minutes after its scheduled time when relaunched.
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
desktop process is stopped. If you want the Shizuki website to accept and display
exact-time reminders too, deploy its companion OpenSpec change
`support-timeprism-at-time-reminders` before saving zero-minute reminders to
the production site. Until then, the production API may reject them.

## Local voice input

The **🎙** button in the native chat dock listens for one Chinese utterance with
the installed Windows `System.Speech` recognizer. Press it again to cancel.
The result is appended to the editable chat draft; it is not sent or converted
to a Todo automatically. Meguri does not save or upload microphone audio for
this control. Windows microphone permission and a `zh-CN` speech recognizer
must be available. If recognition fails, the existing draft stays intact.

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
