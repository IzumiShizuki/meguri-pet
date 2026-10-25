# Verification

## Completeness

Implementation, controls, local regression checks and main-first branch merges are complete. The first release is running, but final corrected deployment and multi-turn web acceptance remain pending because the Windows UAC startup request was cancelled. Do not archive this change yet.

## Correctness

| Requirement / scenario | Implementation | Evidence |
| --- | --- | --- |
| Owner leaves awake PC; native unknown/lock/suspend | main/companion-activity.mjs, foreground-ocr.ps1; shared notice policy | Native unit tests: 599/600-second boundary, invalid samples, lock/resume and zero captures at expiry; real Electron powerMonitor smoke |
| Owner works in another app | companion-reach-outs.ts: independent native expiry and pet-idle clock | Scheduler tests preserve eligibility without resetting conversation idle |
| Expiry during generation / claim / delayed speech | scheduler native refresh; active_until in Core; policy/ledger predicates; speech canStart native probe | Scheduler abort tests, queued Core deadline, news ledger wait, TTS and AudioContext wait tests |
| Enabled bounded observation | trusted stage IPC, in-memory Windows OCR, optional Core ScreenContext | Real Windows OCR fixture; freshness/length/scope tests; provider data-envelope test without tools or fake user turns |
| Disabled/excluded/unavailable observation | default-off preference, fail-closed native observation, ordinary starter fallback | Native disabled/credential/error tests; preference migration; preload scope smoke |
| Inspect controls and coordinated release | CompanionSettings, privacy copy, deploy-companion-branch.ps1 | Browser preview active/inactive/opt-in/480px checks passed; initial release running; corrected activation pending UAC |

## Coherence

No new dependencies, key-content hooks, image persistence, image upload or synthetic user messages. Existing reminders, consent, chat cooldown, quiet hours, delivery ledger and memory authority retained. Explicit screen opt-in is additive. The independent Impeccable reviewer accepted visual clarity and found one speech-start race; the native predicate now runs before synthesis, after synthesis and immediately before playback, with regression tests.

## Completed checks

- Desktop: 66 test files / 483 tests passed; vue-tsc and Vite production build passed. Existing chunk-size notices remain.
- Core: 50 targeted tests passed across reach-out service/controller/provider, canonical provider framing, memory corrections and application; package built with the existing Java 21 runtime.
- Gateway: 13 tests passed.
- Python native-memory/bridge regression: 135 cases passed; only existing Starlette/httpx deprecation warning.
- Native Electron presence and Windows OCR generated-text fixture passed; expiry captures = 0, real user screen captures during fixture = 0.
- Preload smoke returned ok with observation exposed only to the stage.
- OpenSpec strict validation passed.
- Fourteen unrelated tracked work files match pre-change fingerprints; source credential scan and final staged checks are required before committing.
- Six credential/private title exclusions passed under Windows PowerShell 5.1. Chinese title patterns use ASCII Unicode escapes to avoid its default source-decoding ambiguity; the real in-memory OCR fixture still passes.

## Live acceptance follow-up

The initial deployment passed health, memory capabilities, expired activity, stale/oversized screen rejection and a real-model draft with synthetic screen text. An additional ordinary-chat → web-search sequence exposed a pre-existing repetition-counter defect. Read-only inspection of the isolated session confirmed the latest request and canonical context were correct; a new deterministic provider test failed because canonical history counted the current input twice. Canonical counting now starts at zero while legacy prior-history counting stays at one. The fresh-topic/single-message/genuine-repeat regression and all 50 targeted Core tests pass. Live multi-turn web acceptance must be repeated on the corrected deployment.

Deployment evidence and the precise remaining activation boundary are recorded in `docs/deployments/2026-10-25-presence-companion.md`. The existing administrator processes were retained after the final UAC cancellation; no privilege workaround was attempted.
