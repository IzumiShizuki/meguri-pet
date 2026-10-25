## Why

The deployed desktop has idle conversation starters but no native automatic screen observation or system-wide presence gate. A powered-on PC must not imply its owner is awake: the user requires both proactive speech and screen observation to stop ten minutes after the last mouse or keyboard input.

## What Changes

- Gate autonomous reach-outs using trusted OS input age, lock and suspend state; recheck before observation and delivery.
- Keep physical presence independent of the existing conversation-idle timer so activity in another app does not make the feature unreachable.
- Add explicit opt-in local screen OCR, bounded in memory and shared with the same tool-free optional reach-out generator as untrusted context.
- Show activity/observation status and a switch in companion settings; preserve existing quiet hours, cooldowns, daily cap, consent and unanswered limits.
- Implement on master, merge to the requested branch and deploy the coordinated local desktop stack with rollback evidence and live checks.

## Capabilities

### New Capabilities

- `presence-gated-companion`: Recent native input gates automatic conversation starters and bounded, opt-in screen observation.

### Modified Capabilities

None. Existing companion behavior is extended with a stricter activity condition.

## Impact

Electron native presence/capture/OCR seam, narrow IPC/preload contract, companion scheduler/settings, optional bounded screen context in Core reach-out generation, regression tests and deployment evidence. No keyboard-content recording, continuous recording, raw screenshot persistence, automatic memory extraction, new dependencies or changes to explicit user reminders.
