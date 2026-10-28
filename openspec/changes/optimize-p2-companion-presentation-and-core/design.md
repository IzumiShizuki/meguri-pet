## Context

See proposal.md for motivation. The current desktop persists host-owned preferences and conversation checkpoints, cancels local speech by generation, and uses an in-memory 512-entry one-shot list. Reminders are delivered in StageView and AI news enters through artifacts/conversation. Java turn execution already includes durable journal recovery and uncommitted training fixes that must be preserved.

## Goals / Non-Goals

**Goals:** Centralize attention decisions; provide explicit local commitments; make automatic effects at-most-once across reconnect/reload; extract a cohesive response boundary; document actual runtime activation and tested evidence.

**Non-Goals:** Infer promises from model text, schedule while the desktop is closed, rewrite TimePrism, train Qwen, change the wire protocol, implement continuous ASR or streaming TTS, or claim cross-process distributed exactly-once playback.

## Decisions

### Attention policy and explicit state

Use a pure policy and a Pinia companion director. Persist validated host-owned `companion` preferences with initiative `off|low|normal|high`, explicit focus `available|focused`, and reversible presentation choices. Manual focus is user-controlled; desktop observation is not treated as proof of concentration. Existing speech enablement, surface enablement and volume govern speech eligibility. Focus, active conversation and current playback defer unsolicited notices; mute can retain quiet text. Explicit replies keep immediate text delivery. Low/normal/high use bounded cooldowns; off drops unsolicited news but retains user-confirmed commitment follow-up as quiet text. A bounded queue expires stale notices and drains gradually when eligible.

### Commitments and presentation preferences

Users explicitly add local text plus a future due time in settings, complete or cancel commitments, and see follow-up state. Follow-up is an ordinary director notice, not execution of a remote task. Timer ownership belongs to active stage/conversation integration and is disposed on unmount; settings does not own a delivery timer. Completed/cancelled commitments invalidate queued follow-ups. Only minimal explicitly requested local content persists while saveHistory is enabled; withdrawal clears stored content. When history is disabled, validated same-origin BroadcastChannel messages synchronize session-only commitments between the separate settings and stage renderers, including snapshot handshake and lifecycle changes, without storing text on disk. Presentation preference undo snapshots contain only presentation fields, so undo cannot resurrect an earlier commitment lifecycle. Presentation choices do not alter learned memory or model weights.

### Shared integration seam

Director offers/re-evaluates notices, records successful delivery, and exposes delivery subscription plus runtime context `{activeTurn,speaking,surface}`. Decision includes action, text/speech/motion eligibility and reason. News, reminders and commitments use this same path. Playback agent and director agent settle exact TypeScript signatures before writing callsites.

### Durable playback and timing

Use a bounded, validated local ledger keyed by session, turn, effect and kind. Claim is persisted before automatic audio/action execution; completed, skipped and interrupted receipts retain claim identity. Reconnect or restoration can restore static expression/text without replaying claimed audio/motion. Intentional user replay gets a fresh identity. Same-origin concurrent consumers serialize storage claims using Web Locks where available; no distributed delivery guarantee is asserted. A synchronous policy check runs inside the claim lock. If attention changes after a successful claim, an untouched claimed notice can release that receipt and requeue; receipts for already started effects remain consumed. Persist only identifiers/receipt metadata in playback storage. Failure to persist a new automatic claim skips that effect while leaving text and manual replay usable. Cancellation generations invalidate pending cues and audio callbacks; static expression follows visible reply state and queued turn motion follows actual speech start (or completed text when speech is unavailable). Text event consumption does not await TTS generation or playback.

### Core extraction and documentation

Extract native/fallback generation, stream sanitization, latency marks and training supervision into a cohesive response coordinator, retaining orchestration constructors and journal APIs. Further extraction is allowed only for an independently testable responsibility. Existing event ordering, cancellation and training fixes are regression constraints. Record remaining extraction opportunities in the runtime map. Root README distinguishes Python mock harness from launcher-configured Java/Core/memory/TTS runtime; configuration defaults and launcher overrides are documented separately.

## Risks / Trade-offs

- Local follow-up requires a running stage → show this prerequisite in settings and docs; preserve TimePrism as the remote reminder path.
- Claim-before-play can skip an effect after a crash → intentional replay remains available; preventing automatic duplication is the chosen trade-off.
- Existing dirty files contain prior work → agents own disjoint files and preserve current content; primary reviews final deltas and runs integration checks.
- Audio/Live2D resources vary by machine → separate automated fake-audio tests from actual listening/character timing acceptance.

## Migration Plan

Normalize missing/invalid companion fields to conservative defaults. Retain existing preference version and deep merge behavior. Introduce a separate versioned ledger key, bounded retention and session filtering; avoid persisting speech text. Rollback code leaves existing preference documents readable and disables new director behavior by initiative off/reset. Run desktop unit/typecheck/build and Java runtime regressions before recording acceptance. No remote deployment or automatic OpenSpec archive is part of delivery.
