## Context

See `proposal.md` for the user goal and `specs/pixpin-screenshot-handoff/spec.md` for observable behavior. PixPin 3.5.5.1 is installed at `D:\Program Files\PixPin\PixPin.exe` (verified through the Windows uninstall entry and installer source), and a PixPin process was running during this audit. The executable's Authenticode signature is valid. No screenshot/clipboard handoff has yet been exercised.

The native desktop currently stages clipboard images only on an explicit composer paste. `apps/desktop-meguri/src/main/attachment-policy.mjs` converts a native image to a bounded PNG `inline_attachment` (5 MiB per image); `ChatDock.vue` stages it and `conversation.ts` sends it through the existing local Core path (10 MiB readable-attachment aggregate). A vision-capable route is required for image turns. `add-desktop-observation-runtime` added policy and semantic models but intentionally did not wire native capture, PixPin, or ordinary `screen_read` permission.

PixPin's official Windows script entry point is `PixPin.exe -r "pixpin.screenShot(ShotAction.Copy)"` (or `-f` for a script file). Current official docs say an instance must already be running. The documented API also supports direct rectangles, active-window capture, OCR copy, and system commands. It does not document an HTTP API or a reliable callback that delivers a manually selected image to an external application. The design must therefore prove the completion signal locally before using it for automatic sending. PixPin 3.5 can copy either standard image data or an image *file* to the clipboard, depending on its “复制图像为文件” setting; the current Meguri paste bridge assumes `clipboard.readImage()` and may not cover the file-backed variant.

## Goals / Non-Goals

**Goals:**

- A PixPin selection produces exactly one validated screenshot attachment when initiated from Meguri.
- The user chooses whether to edit a question or send a default question before capture starts.
- The existing manual PixPin-copy/Meguri-paste path remains usable without setup.
- PixPin failure, cancellation, stale clipboard, missing vision route, and turn failure produce recoverable states.

**Non-Goals:**

- No periodic Glance, continuous clipboard watcher, Screenpipe, Watch task, automatic OCR, or long-term memory write.
- No PixPin private API, patch, binary modification, or assumed post-capture callback.
- No enabling `screen_context`/`screen_read` handshake flags as a side effect of user-selected image attachment.
- No mandatory new screenshot format or hosted upload endpoint.

## Decisions

### 1. Ship the user-owned workflow first

The primary entry points live in the Meguri chat UI as **截图问 Meguri** and **截图直接发送**. Draft mode opens the composer with the image staged and preserves existing text and attachments. Direct-send mode uses a visible fixed prompt such as `请看看这张截图，告诉我你注意到什么。` and sends through the same `conversation.send` path. If there is already a turn/capture or adding the image would exceed aggregate limits, fail before starting a new send and leave the current draft intact.

Alternative considered: make every screenshot auto-send. Rejected because it removes the opportunity to specify the actual question and would make a generic screenshot action unexpectedly transmit image content.

### 2. Keep PixPin outside the renderer's trust boundary

The Electron main process owns executable discovery, launch, capture session state, and native clipboard reads. The renderer gets only narrow preload methods/events (start, cancel, status, result), never an arbitrary executable path, script string, or shell command. Use `spawn`/equivalent with an executable and argument array; do not interpolate renderer-controlled text into PixPin scripts or a shell. Accept a configured absolute `PixPin.exe` only after verifying it is a local regular file, and use bounded discovery paths. Startup guidance points to the official PixPin site; the app does not download or install it silently.

Alternative considered: let Vue invoke a local helper or PixPin CLI directly. Rejected because an untrusted renderer must not gain general process execution or clipboard authority.

### 3. Model capture as a one-shot state machine

The main process manages at most one session with `captureId`, mode (`draft`/`direct_send`), start time, deadline, initial clipboard generation, and state (`starting`, `selecting`, `received`, `cancelled`, `timed_out`, `failed`). Register receipt before invoking PixPin, temporarily exclude/hide Meguri overlays during selection, restore them in every exit path, and stop receipt immediately after one accepted image. Cancel/timeout/app exit invalidates the `captureId`; late events cannot attach to another draft or send. A capture started in one conversation remains associated with that session even if UI focus changes; safest first release cancels on session/window change.

The implementation spike must establish a reliable Windows clipboard **change generation** signal (`GetClipboardSequenceNumber` or a packaged local notification adapter using `WM_CLIPBOARDUPDATE`) and confirm PixPin's selection places an image on the clipboard. It must also check whether the clipboard owner can be associated with the launched PixPin process/window (for example via `GetClipboardOwner` and its process ID). A pixel hash alone is insufficient: two deliberate identical captures must both be receivable, while the pre-existing image must never be mistaken for a new result. Exercise both standard-image and file-backed clipboard settings. For a verified file-backed PixPin result, main may read only that one canonicalized, size-bounded local image file and turn its bytes into the same inline attachment; do not pass the PixPin cache path as an `everything` reference, because the existing local-path policy rejects AppData. On a new generation, accept only a non-empty image attributable to the active PixPin selection within the active time window, then reuse image validation and existing staging limits. Avoid clearing or overwriting the user's clipboard. No listener persists outside the session.

Alternative considered: watch PixPin's quick-save directory. Rejected for the first release because it depends on mutable global PixPin configuration, creates files, and cannot reliably attribute a file to the active capture. If the clipboard completion signal cannot be packaged reliably, defer automatic capture/send and retain explicit paste/shortcut handoff rather than weakening the one-shot guarantee.

Implementation note: the native adapter uses `GetClipboardSequenceNumber`, `GetClipboardOwner`, and `GetWindowThreadProcessId` from a short-lived Windows PowerShell process, started only for an active capture. It accepts only changes owned by a PID discovered for the selected local `PixPin.exe`, then converts a stable standard-image clipboard result through the existing 5 MiB attachment validator. File-backed clipboard contents are rejected with setup instructions until their format and file lifetime can be verified. The stage window is hidden during selection and restored on every terminal state. Because PixPin documents no post-selection callback to Meguri, `Ctrl+Alt+Shift+P` explicitly imports a PixPin screenshot started from PixPin itself.

### 7. Let the visibility shortcut recover an obscured stage

The stage can remain logically visible while a topmost PixPin screenshot/editor window covers it. Treat a visibility-shortcut press on a visible but unfocused stage as a request to recover the window: re-apply the user's saved always-on-top setting, show it, and focus it. Hide the stage only when it is already focused. This keeps the shortcut useful after an external PixPin capture without changing the user's saved topmost preference.

### 4. Keep PixPin-side initiation behind a feasibility gate

Official PixPin custom actions can call scripts or system commands, but the docs do not promise that a command placed after `screenShot(ShotAction.Copy)` runs only after manual selection finishes. This release uses a Meguri global shortcut **接收剪贴板截图** (`Ctrl+Alt+Shift+P`) that explicitly imports the current clipboard image through the existing validator. It keeps PixPin-side capture useful without assuming an undocumented callback or running a clipboard watcher in the background. PixPin-specific one-click menu integration remains unsupported until the installed version's execution order is verified.

Alternative considered: implement a persistent clipboard watcher that detects all PixPin copies. Rejected because origin cannot be safely inferred from clipboard contents and the user did not authorize continuous monitoring.

### 5. Reuse the existing multimodal attachment path

Both modes create the existing `inline_attachment` shape. Draft mode calls the same staging checks as paste. Direct-send mode calls `conversation.send(defaultQuestion, [newImage])` without running `ChatDock.send()`, which currently clears the user's draft and all staged attachments before awaiting the turn. On failure, the store retains the validated image and default question for retry while preserving any pre-existing draft. The current 5 MiB PNG and 10 MiB aggregate limits remain; no implicit compression or truncation. PixPin captures use a capture-specific `reference_id`, so deliberate recaptures of identical pixels remain distinct. If route readiness cannot be checked locally, preserve the image and surface the Core's existing actionable vision error rather than claiming successful understanding.

Alternative considered: send PixPin OCR text as a substitute when a vision model is unavailable. Rejected for this change because OCR loses visual information and silently changes the user's requested image task.

### 6. Keep user-selected Point separate from ambient observation

A deliberate region selection is a one-time attachment authorization as described in the Notion baseline. It does not establish an `ObservedWindow`, enable `screen_read`, or create a durable `ScreenContext`. A later OpenSpec change can connect the real observed-window identity/rectangle to PixPin `directScreenShot(...)`, apply observation privacy before capture, use OCR/VLM as needed, and generate TTL-bound semantic context. That later path also needs capture isolation for Meguri overlays. For this change, temporarily hiding the overlay prevents it appearing in a user's own PixPin selection where possible.

## Planned Code Touchpoints

| Area | Expected changes |
| --- | --- |
| `apps/desktop-meguri/src/main/` | PixPin discovery/launch and bounded one-shot capture coordinator; trusted IPC registration; optional Windows clipboard change adapter; overlay restore. |
| `apps/desktop-meguri/src/main/attachment-policy.mjs` | Reuse PNG validation; add a narrowly scoped file-backed clipboard conversion if live verification requires it, and distinguish deliberate same-image recaptures. |
| `apps/desktop-meguri/src/shared/channels.cjs`, `desktop-contract.ts`, `src/preload/index.cjs` | Narrow capture commands, status/result event contract, and cleanup methods. |
| `apps/desktop-meguri/src/renderer/components/ChatDock.vue` | Two actions, capture progress/cancel/error UI, draft staging, direct-send trigger that does not clear unrelated draft text/attachments. |
| `apps/desktop-meguri/src/renderer/stores/conversation.ts` | Reuse existing send/retry path; change only if a shared programmatic send entry is needed. |
| `apps/desktop-meguri/src/main/global-shortcuts.mjs` | Optional explicit clipboard-import shortcut, with conflict reporting. Existing `Ctrl+Alt+H/C` bindings must be preserved. |
| `apps/desktop-meguri/scripts/`, `ops/` | Windows helper build/package wiring if required, official installation/setup instructions, and live smoke procedure. |

## Risks / Trade-offs

- [PixPin 3.5.5.1 is installed but script timing is unverified] → First implementation task is a versioned local spike; keep fallback manual paste and do not mark direct integration complete without live capture.
- [PixPin may copy an image file rather than standard image bytes] → Test its clipboard modes and support only a verified, bounded file-backed result; never broaden general AppData file authority.
- [Clipboard events from another application race PixPin] → Bind receipt to a short capture session, new clipboard generation, and verified PixPin owner; reject late events and test interference explicitly. If source attribution cannot be made reliable enough, use explicit paste/shortcut rather than auto-send.
- [Identical screenshot bytes do not imply no new capture] → Use OS clipboard generation, not only image hashes.
- [PixPin may require elevation or a foreground instance] → Detect absence/failure and show actionable guidance; do not escalate Meguri automatically.
- [Overlay may appear in the selected region] → Hide/exclude it during capture and restore in `finally`; test cancellation, timeout, and multiple monitors/DPI.
- [The local vision route may be unconfigured] → Keep the attachment and show the existing configuration error; do not substitute OCR silently.
- [Current working tree contains unrelated uncommitted changes] → During implementation, inspect the current diff and edit only the selected integration surfaces; do not reset or overwrite unrelated work.

## Migration Plan

1. Keep this OpenSpec change as the implementation source of truth. No migration or runtime setting changes are made during planning.
2. When implementation begins, use the existing PixPin 3.5.5.1 installation at `D:\Program Files\PixPin`; do not download another copy. Keep the executable path configurable rather than hard-coding the current machine into shipped defaults.
3. Complete the clipboard/PixPin proof of concept, then implement the main/preload/UI path behind a default-off availability check. Preserve current paste behavior throughout.
4. Validate unit/contract/build tests, then perform manual Windows smoke tests for both modes, cancellation, timeout, same-image recapture, clipboard interference, missing PixPin, missing vision model, and failed-send retry.
5. Roll back by disabling the PixPin actions and one-shot bridge; the existing paste and conversation paths remain intact.

## Future Follow-Up

The Notion Glance path—`ObservedWindow` identity and rectangle, privacy policy, PixPin OCR or screenshot, semantic `ScreenContext`/TTL, and optional proactive expression—should be a separate OpenSpec change after user-triggered Point is working. It requires native window attachment and capture isolation, which this screenshot handoff does not provide.
