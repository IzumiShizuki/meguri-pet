## Context

The native Vue desktop owns idle reach-outs; the older AIRI steward worktree is a separate, uncommitted baseline and does not run automatic capture. Current reach-outs wait 15/45/120 minutes since pet interaction and have no OS-input test. The user requests main-first implementation and deployment from `codex/companion-memory-corrections`. Prior desktop/Core changes are still uncommitted, so a scoped integration checkpoint is needed for a reproducible merge; unrelated AstrBot/Bilibili/training changes remain untouched.

## Goals / Non-Goals

**Goals:** Reliable native presence gating, one bounded opt-in observation at an eligible reach-out attempt, visible controls, tested branch ancestry and coordinated local deployment.

**Non-Goals:** Continuous capture, keyboard hooks/content logging, image upload, historical screen recording, copying the other worktree, changing explicit reminders or replacing the chat backend.

## Decisions

1. Electron `powerMonitor.getSystemIdleTime/getSystemIdleState` provides native input age. Lock/suspend events invalidate presence immediately. Resume requires a later inferred input. Fresh main-process sampling occurs before capture and after model generation. Renderer eligibility uses the native last-input expiry, not only a boolean, so the exact 600-second boundary remains enforced during ledger claims. Probe failures fail closed.
2. Keep pet interaction and OS presence as separate clocks. Activity in another app permits a future starter but never restarts its conversation-idle wait. Existing quiet hours, daily limit, unanswered marker, focus/voice/editing gates and cooldown remain in force.
   Apply the same absolute native expiry to unsolicited news at the shared delivery policy, including deferred/claimed notices. Explicit reminders and confirmed follow-ups retain their existing behavior.
   Pass a native status predicate through unsolicited notice speech; check before synthesis, after synthesis and after AudioContext resume immediately before audio starts. A delayed TTS response cannot start speaking after inactivity or lock, while explicit playback/reminders retain their existing behavior.
3. Add a narrow trusted-stage observation IPC; settings may read sanitized status but cannot capture. A static bounded Windows PowerShell/WinRT helper captures only the foreground window and runs installed Windows OCR in memory. It excludes the pet, lock/credential/private window titles, rechecks foreground identity, limits pixels/text/time and never uses clipboard/files. Local provider failure returns no observation. Microsoft Windows OCR documentation supports the API; no package install is required.
4. Extend the existing draft request with optional bounded `screen_context` (text, app and observation timestamp). Core validates freshness and scope, passes it as a separate untrusted data envelope to the tool-free provider, and retains existing attempt/delivery caching and assistant-only acknowledgement. Ordinary generation remains backwards compatible; no synthetic chat turn is used.
   The new host also supplies an `active_until` deadline derived from native input. Core rejects expired/excessive deadlines and rechecks before the provider call, preventing a queued request from starting model work after the activity window expires. Older API callers remain compatible; the deployed native scheduler always sends the deadline.
5. Add `screenObservationEnabled` to companion preferences, default false. The user's explicit request authorizes enabling it in the deployed local profile after backing up that profile; all other preferences/credentials remain. Settings show presence and observation result metadata, never OCR contents.

## Risks / Trade-offs

- OCR misses non-text visuals or unreadable/unsupported languages → no claim of full visual understanding; skip failed/empty captures and retain normal grounded starters.
- Secret/private foreground windows → exclude common credential/private titles locally before pixels and reject unsafe recognized text; raw pixels never leave memory.
- Lock/activity changes while waiting → recheck natively at each boundary and use expiry-aware final delivery predicate.
- Prior dirty workspace → commit only the desktop/Core dependency set as a baseline; fingerprint unrelated files, implement on master, merge back, then build from the selected branch. No force reset or push.
- Four local components must agree → preserve prior artifact/config snapshots and use existing launchers; validate health, capabilities, native presence, OCR fixture and live draft behavior after restart.

## Migration Plan

No schema migration or new dependencies. Preserve deployed artifacts/configuration without logging secrets. Commit the tested desktop integration baseline on master, implement/test this change there, and fast-forward/merge the requested branch to master. Build Java and renderer; restart the selected local memory bridge, Core, gateway and desktop through established helpers, enable the user-authorized screen switch, and verify read-only status plus isolated test sessions. Keep rollback assets and report active PIDs/hashes. Do not mutate real memory facts for acceptance.
