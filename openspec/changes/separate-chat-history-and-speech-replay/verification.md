## Verification report: separate-chat-history-and-speech-replay

Verified 2026-10-07. Implementation and documentation are complete; this change remains unarchived.

| Dimension | Result |
| --- | --- |
| Completeness | 7/7 tasks; both requirements implemented |
| Correctness | All 10 scenarios covered by unit tests and isolated Electron checks |
| Coherence | Explicit history gate, independent tool sizing, shared playback controls, original speech metadata, and request-token cancellation follow the design |

## Implementation and scenario evidence

| Requirement / scenario | Implementation | Validation |
| --- | --- | --- |
| Default/streaming small bubble | `ChatDock.vue:79`, `ChatDock.vue:675` | One bounded preview above input; streamed text does not expand window; unfinished replies have no replay control |
| Explicit history and collapse | `ChatDock.vue:556`, `ChatDock.vue:718` | Eight actual button expand/collapse cycles preserve draft, fitted native bounds, and portrait position |
| Independent functional bubbles | `ChatDock.vue:713`, `styles.css:146`, `styles.css:182` | Todo, reminder review, and attachment review all report `historyVisible: false`; close restores compact mode; Todo list and composer remain accessible |
| Failed send | `ChatDock.vue:799` | Error/retry remains inside compact window, history hidden, portrait unchanged |
| Compact reply replay | `MessageReplayButton.vue`, `conversation.ts:449` | Real UI clicks invoke local synthesis using captured text/style/speed; no message is added or history revealed |
| Automatic speech disabled | `speech-playback.ts:51`, `speech-playback.ts:67` | Manual playback succeeds with automatic speech disabled; volume/speed and saved preferences remain unchanged; expanding chat during playback does not cancel it |
| Historical original speech | `conversation.ts:420`, `MessageContent.vue:36`, `GalgameStage.vue:123` | Cue persistence/restoration and historical replay unit tests; actual history and Galgame buttons request the selected older reply |
| Stop/replace playback | `conversation.ts:443`, `conversation.ts:458` | Deferred request replacement test preserves the newer selection; actual compact and history stop controls cancel playback |
| Playback failures | `speech-playback.ts:126`, `speech-playback.ts:137` | HTTP synthesis failure, audio error, feedback at the selected reply, and successful retry without draft/message loss |
| Active voice call | `ChatDock.vue:684`, `ChatDock.vue:728` | Simulated listening phase disables manual replay; existing live-call lifecycle regression tests pass in full unit run |

## Checks

- Focused conversation, speech playback, and native stage layout tests: **36/36 passed**.
- Vue/TypeScript checking: **passed**.
- Production Vite build: **passed**; existing large-chunk advisory remains.
- Isolated Electron bubble check: **passed**, zero renderer console errors. Captures compact, playback, playback failure, send error, history, Todo, reminders, attachments, narrow windows, skills, work, and Live2D. PNG portrait remains 350 × 540 CSS pixels. Live2D remains the same canvas instance, 350 × 540 CSS pixels and 700 × 1080 canvas pixels across expansion/collapse. The check waits for the finite entrance animation before comparing measurements.
- Preload bridge smoke: reports **`ok: true`** for scoped bridge availability.
- OpenSpec strict validation and affected tracked-file whitespace check: **passed**.
- Existing local TTS health probe: **`status: ok`, `ready: true`**. A short synthesis probe using the replay text/style generated a **285,484-byte RIFF/WAV** sample at `.codex-runtime/bubble-layout-smoke/speech-replay.wav`. No speaker playback or production conversation/Todo/memory write was performed. Electron playback lifecycle tests use silent fake audio.

## Existing regression warning

The full desktop unit suite reports **327 passed / 1 failed (328 total)**. The pre-existing assertion in `src/renderer/services/reminder-flow.test.ts:33` expects clarification for “明天3点提醒我开会”, while the concurrently developed natural-language time inference returns an editable review. This failure existed before this follow-up and is outside history/replay behavior. The relevant scenario and every new test pass; do not treat the full suite as entirely green.

No incomplete implementation tasks or requirement/design discrepancies were found for this change. The existing reminder regression is recorded as a warning; no change was archived.
