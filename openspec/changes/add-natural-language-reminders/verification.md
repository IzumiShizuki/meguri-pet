## Verification Report: add-natural-language-reminders

Implementation verified on 2026-10-04, Asia/Shanghai, before the subsequent commit/deployment request. This change remains unarchived.

## Summary

| Dimension | Result |
| --- | --- |
| Completeness | All 11 scoped tasks completed; 6 requirements mapped below |
| Correctness | 248 desktop unit tests pass, including 83 reminder-related tests; loopback HTTP and rendered UI verification pass |
| Coherence | Deterministic shared parser, explicit confirmation, account-bound durable journal, local polling and unsupported action/offline boundary match the design |

## Requirement and scenario evidence

| Requirement | Implementation | Evidence |
| --- | --- | --- |
| Shared intent parsing and clarification | `src/shared/reminder-intent.mjs`, `src/renderer/services/reminder-flow.ts`, ChatDock | Text/voice equivalence; dates, Chinese numerals, offsets, ambiguity, invalid/past/incomplete or second-precision times, year/leap/DST boundaries; 83 related tests also pass with `TZ=America/New_York` |
| Explicit review and capability limits | ReminderReview, ReminderFlow, native client behavior/timezone checks | No POST on prepare; correction still needs confirmation; unsupported order/shell/message/recurrence/offline intents blocked; ambiguous reminder-versus-action asks clarification; rendered review and voice draft checks pass |
| Server-confirmed creation and safe retry | `src/main/shizuki-timeprism.mjs`, `shizuki-write-journal.mjs` | Exact minute/zero-offset fields checked; missing fields rejected; definite 400 retries; lost response recovered after restart without second POST; 500/invalid/empty/offline/removed or duplicated marker stays uncertain; new request ID cannot bypass an identical uncertain request; damaged journal fails closed; credentials absent from journal; actual authenticated loopback HTTP tested |
| Server-acknowledged edit and cancellation | TodoPanel, stage-only update/complete IPC, client full PUT | Title/time edit, selected cancellation, preservation of project/detail/order fields, idempotent cancellation retry after lost response; frozen account check prevents writes after account switch; real rendered cancellation verified |
| Honest local trigger and delivery status | `shizuki-reminders.mjs`, main delivery integration, StageView/SettingsView | Actual 30-second timer test; persisted dedupe; twelve-minute late window; stopped runtime emits nothing; failed sync clears snapshots; per-record freshness; acknowledged edit/cancel invalidates older in-flight read; corrupt/unwritable dedupe suppresses trigger; delivery callback error and in-process retry covered; isolated real Electron second-process lock smoke passes |
| Separate verification evidence | README and this report | Read-only production results below; mock and loopback tests clearly separated; no production writes/messages; PixPin/TTFT artifacts untouched |

Paths in this table are relative to `apps/desktop-meguri`, except OpenSpec documentation. Related tests are `reminder-intent.test.ts`, `reminder-flow.test.ts`, `shizuki-write-journal.test.ts`, `shizuki-timeprism.test.ts` and `shizuki-reminders.test.ts`.

## Checks performed

- `pnpm test:desktop`: **42 files, 248 tests passed**, followed by successful native Electron preload and single-instance smoke checks. New pending-create/update methods are available only to the trusted stage renderer; settings/startup/studio cannot invoke them. No general scheduled executor bridge exists.
- `pnpm typecheck:desktop`: passed (`vue-tsc --noEmit`).
- `pnpm build:desktop`: passed (Vite renderer build). No dependencies installed or toolchain overwritten.
- All five related test files with `TZ=America/New_York`: **83 tests passed**. Shanghai epochs remain independent of the host's DST rules.
- `scripts/reminder-ui-smoke.py`, using installed py314 Playwright and headless Edge, with a local Vite preview and mocked host: review-before-write, clarification, unsupported action, voice draft, failure/retry request reuse, selected cancellation passed. Requests to real TimePrism/Core/TTS/gateway were blocked. Screenshots were inspected under ignored `output/reminder-ui-smoke-20261004-2015/`; this is UI evidence, not an end-to-end production reminder.
- `openspec validate add-natural-language-reminders --strict`: passed.
- `git diff --check`: passed. HEAD remains `f068f85f05612949ad0d3b228e98dbfb9fc67513`, branch `master` remains two existing commits ahead of `origin/master`. No reset, rebase, commit amendment, deployment or user-data migration was performed.

## Production verification — read-only, not E2E

| GET endpoint | Observed result | What it proves |
| --- | --- | --- |
| `https://site.shizuki.online/` | HTTP 200, HTML | Site reachable from this machine |
| `https://site.shizuki.online/v3/api-docs` | HTTP 200, same 4490-byte frontend HTML | This URL exposes no usable API/capability metadata |
| `https://site.shizuki.online/api/v1/light-apps/todos` | HTTP 401, problem JSON `Login required` | Todo route requires authentication; no minute-write evidence |
| `https://bot.shizuki.online/meguri-core/v1/reminders/timeprism` | HTTP 401, HTML from access layer | Authentication boundary encountered; does not establish Core controller deployment or QQ delivery |

The local `shizuki-site` companion change contains zero-minute normalization, but was read only and is not deployment evidence. No production login, Todo write, real reminder, QQ message or cleanup-dependent live record was created. There is therefore nothing to clean on production. Actual minute persistence/readback, OS notification presentation, microphone recognition, GPT-SoVITS playback and QQ delivery were not validated against production or physical hardware. Core and AstrBot were not modified, so Java/Python service suites were not rerun.

## Remaining operational limits

- **Production E2E remains unverified.** Before advertising live delivery, use an authenticated, identifiable cleanup-safe Todo in an isolated owner test setup, verify fields and due trigger, then remove the test record. Do not notify another person. A server dropping fields is already handled as unconfirmed by the client.
- **No offline worker or action executor is implemented/enabled.** The supported capability is one-shot reminder notification from a running desktop. Scheduled order checks, arbitrary shell/control/messages and recurring tasks cannot be confirmed as arranged. Always-on support requires a separate persistent scheduler/worker with capability permissions, execution idempotency and result records.
- Dedupe reserves an occurrence before issuance. A crash at the reservation/presentation boundary can lose presentation; there is no human-read guarantee. The live-process callback retry preserves existing local-card suppression and QQ outbox behavior; it does not guarantee retry of an unqueued QQ side effect after a crash. Core receipt remains separate from AstrBot successful send.
- The native single-instance primitive was tested in isolated profiles. The existing Windows UAC/elevation handoff order was retained and reviewed; a real administrator relaunch was not performed because it would change the user's running setup.
- The parser deliberately supports a bounded Chinese grammar. Unknown dates/approximate times and foreign timezones need an exact Shanghai date/time supplied in review. Shanghai modern wall times have a fixed UTC+08:00 offset; foreign DST conversion is not offered.

## Final assessment

No critical implementation/spec mismatch found within the declared local-reminder scope. The production and hardware limits above remain explicit verification warnings. The unrelated PixPin and TTFT changes retain their existing incomplete checkboxes. At this implementation-verification checkpoint, the change had not been archived or deployed.

## Subsequent authorized local deployment

After the user's commit/deployment request, the implementation was committed as `69c696a` and its renderer rebuilt. The native bat first launched it at 20:29:54 on 2026-10-04. After adding the separately specified tray extension (`35addb6`), the final native app was restarted at **20:45:15 Asia/Shanghai**; a new stage renderer and successful tray registration were observed. See `../add-desktop-system-tray/verification.md` for the local deployment receipt. Existing Git history and profile files were preserved. This is local desktop deployment only: it adds no server worker, scheduled action executor or production TimePrism/QQ E2E evidence. Both changes remain unarchived.
