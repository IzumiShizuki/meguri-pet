# Verification: optimize-p2-companion-presentation-and-core

Verified on 2026-10-09 against the spec-driven proposal, design, both delta specs and tasks. The primary reviewed integration and ran the final desktop suite/typecheck/build; Luna agents implemented the code and focused regressions. The current working tree includes prior work, which was preserved. This change is not archived or deployed.

## Scorecard

| Dimension | Result |
| --- | --- |
| Completeness | 13/13 tasks complete; 6/6 requirements implemented |
| Correctness | All 15 scenarios mapped to implementation and evidence below |
| Coherence | Attention policy, explicit consent, claim-before-effect, cancellation generations and bounded response extraction follow design |

## Requirement and scenario evidence

Paths below are repository-relative. Renderer paths are under apps/desktop-meguri/src/renderer/ unless stated otherwise.

| Requirement / scenario | Implementation | Evidence |
| --- | --- | --- |
| Attention-aware delivery: focus deferral | services/companion-policy.ts; stores/companion-director.ts; views/StageView.vue | companion-policy.test.ts covers focus/active turn/playback; companion-director.test.ts covers deferred draining; real Electron scheduler defers during focus |
| Muted delivery remains quiet | Policy checks TTS, volume and active surface; shared notice delivery | companion-policy.test.ts and companion-notice-delivery.test.ts; Electron result speechCalls=0 while one text message is delivered |
| Initiative off | Policy drops news and retains quiet confirmed commitments | companion-policy.test.ts covers off, queue limits, cooldown and expiration |
| Explicit commitments: due follow-up | Director add/offer/record lifecycle; notice ledger claim | companion-director.test.ts and companion-notice-delivery.test.ts; Electron shows one notice, followUpDeliveredAt and completed receipt |
| Cancellation while deferred | removeDeferredCommitment and synchronized terminal lifecycle | companion-director.test.ts; companion-session-sync.test.ts; real settings/stage cancellation sync |
| Storage consent withdrawal | src/main/desktop-preferences.mjs normalizes commitment text away; director consent watch and ephemeral transport | src/main/desktop-preferences.test.ts; director/session-sync tests; Electron has no persisted commitments or history text with saveHistory=false |
| Reversible preferences: reset and undo | stores/desktop-preferences.ts:124-144 projects only four scalar presentation fields | Reset branch inspected against validated defaults; director regression and actual UI undo restore choice without restoring commitment lifecycle |
| Existing preference migration | src/main/desktop-preferences.mjs preserves unrelated fields and bounds malformed companion data | Main desktop-preferences normalization/deep-merge tests |
| Durable claims: reconnect/reload | services/playback-ledger.ts:78 claims before effects; conversation and shared notice consumers use persistent identities | playback-ledger.test.ts reload case; conversation and notice tests suppress an already consumed effect after rebuild |
| Session isolation | Ledger key includes session/turn/effect/kind | Ledger and conversation tests allow a distinct effect in a new session |
| Storage failure | Claim fails closed; text event consumption is independent | Ledger malformed/storage-failure tests; conversation accepts text without awaiting speech |
| Cancellation-safe timing: slow synthesis | stores/conversation.ts dispatches TTS asynchronously and applies motions on actual speaking callback | New behavior-level conversation test holds synthesis pending, renders text first, then triggers motion only on onSpeaking(true) |
| Replacement and late callback | Request/interruption generations; speech service guards local AudioContext and playback callbacks | conversation.test.ts covers replaced-turn callbacks; speech-playback.test.ts covers late resume resolution/rejection and replacement state |
| Snapshot recovery | stores/presentation.ts applyStaticCue strips motion; conversation snapshot uses static path | presentation.test.ts restores expression without one-shot action; motion revision only advances on explicit motion |
| Deliberate manual replay | stores/conversation.ts replaySpeech creates a fresh manual request without submitting a turn or editing preferences | conversation/speech-playback tests cover already claimed replies, disabled automatic speech, stop and replacement |

The integration seam uses the same director and durable delivery helper for AI news, TimePrism reminder metadata and explicit local follow-ups. Notice policy is rechecked inside the claim lock and after awaiting it. A claim released after attention changes is restricted to an untouched claimed receipt; started effects remain consumed. Regression tests cover this race.

Per-store runtime registries use Vue toRaw(this). Local Pinia devtools wraps each action in a fresh Proxy; direct WeakMap keys had broken session transport, listeners, scheduler disposal and serialized flushes in the actual renderer. A fresh-proxy-per-action regression and the two-window Electron run verify the repair.

## Core and documentation coherence

TurnResponseCoordinator.java:51 owns native/fallback generation, sanitization and ordered deltas, latency marks, weather response augmentation, alternative comparison and single-observation fallback. TurnOrchestrator retains public constructors, retrieval/context acquisition, idempotency, lifecycle, journal/replay and terminal emission. No public wire or journal schema change was introduced. The class is still large; the runtime map records remaining seams rather than claiming the decomposition is complete.

DurableNativeStreamingTest and TrainingFeedbackServiceTest: 14 focused tests passed. Broader runtime/training/RuntimeWebControllerTest: 17 suites, 77 tests, 75 passed, 2 conditional skips, zero failures/errors. Existing training scope and archive-failure protections remain covered. Root/Core/desktop README now distinguish the Python harness from the launcher-configured Java runtime and prefer the existing activation script. Launcher defaults, rollback and manual resource checks are documented in docs/p2-runtime-map-2026-10-08.md.

## Final validation

- Desktop: 62 unit-test files / 385 tests passed; vue-tsc and Vite production build passed.
- Electron preload, instance and tray regression checks passed.
- scripts/p2-companion-smoke.mjs: standalone exit 0, ok=true; actual settings controls and two same-origin windows; snapshot/cancel/delivery sync; preference undo; real 15-second scheduler with a synthetic due fixture; focus deferral; one text message; completed ledger receipt; no TTS calls or persisted commitment text; no console errors or other external requests. Vite child was cleaned up after exit. The decorative gateway sprite was intentionally blocked and recorded separately.
- Git diff --check has no whitespace errors; existing line-ending warnings remain.
- Planning and implementation checklist validated separately with OpenSpec strict validation; completion is based on the implementation evidence above, not merely artifact status.

## Issues and acceptance boundaries

CRITICAL: None. No missing scoped implementation or blocking automated failure remains.

WARNING:

1. Real Windows audio-device playback, GPT-SoVITS listening quality and Live2D perceived timing have not been manually accepted. Follow the existing desktop resource/startup instructions and listen to a slow response, cancellation and manual replay on the selected resources before production acceptance. Fake audio and hidden preview windows only establish control-flow behavior.
2. The two PostgreSQL process-restart integration tests were skipped because MEGURI_RECOVERY_TEST_DSN_FILE was not configured. Run the separate Turn Runtime activation runbook with its authorized database fixture before claiming real database restart recovery. This change did not alter that deployment path.

SUGGESTION:

1. TurnOrchestrator remains 2,640 lines after one cohesive extraction. Use the documented idempotency/recovery and context boundaries for a separate reviewed decomposition; avoid coupling additional extraction to this behavior change.
2. Vite retains the existing >500 kB chunk warning. Consider a separately measured bundle-splitting change if startup measurements warrant it.

The playback ledger intentionally retains at most 512 receipt metadata entries; claim-before-effect can skip an effect after a crash. Local commitments require an active desktop and ephemeral commitments do not survive desktop exit. These match the design trade-offs. No cross-process exactly-once or unlimited retention guarantee is asserted.

Final assessment: no critical issues; the scoped P2 implementation and automated acceptance are complete, with the resource/deployment checks above explicitly outstanding. No archive was performed.
