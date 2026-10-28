# Verification: fix-compact-chat-bubbles

Date: 2026-10-07 (Asia/Shanghai).

## Summary

| Dimension | Result |
|---|---|
| Completeness | 7/7 tasks complete; all 3 requirements and 8 scenarios mapped |
| Correctness | Native layout, isolated Electron UI, real local Live2D canvas, typecheck, and production build pass; full unit suite has one unrelated existing expectation failure |
| Coherence | Shared host/renderer portrait geometry, temporary tool fitting, stable mounted composer and Live2D canvas follow the design |

## Requirement and scenario mapping

| Scenario | Implementation and executed evidence |
|---|---|
| Collapse and reopen history | ChatDock.vue:76,79,82,660,692 derives panel mode and latest reply while keeping the composer mounted; visual smoke verifies full history and unchanged draft over 8 toggles |
| Streaming reply in compact mode | Latest companion reply feeds the compact bubble; visual smoke updates a streaming message and confirms the window remains compact |
| Todo from compact presentation | Todo uses temporary expansion instead of changing the saved history preference; visual smoke opens 18 preview todos, checks scrolling, footer separation and composer access, then closes back to compact with the draft intact |
| Reminder and attachment review | Reminder phase/pending requests and staged resources select the large bubble; visual smoke opens/closes both, including the attachment remove control and unoccluded composer |
| Skills and work | StageView.vue:43,45,260 aggregates all open panels into the validated ambient-panel host request; visual smoke checks each panel beside the character and restoration on Escape |
| Repeated expand and collapse | stage-layout.test.ts covers 12 controller toggles, manual resize and fractional-DPI no-op fitting; visual smoke executes 8 actual window toggles and a 920x760 resize, restoring compact dimensions without accumulating drift beyond initial Windows rounding |
| Legacy size and constrained display | Versioned geometry migrates old tall saved bounds in native tests; negative-coordinate/narrow work area fitting is tested; visual smoke checks 360x560 viewport fallback and fixed portrait dimensions |
| Return from Galgame | Controller tests restore both compact and temporary expanded ambient bounds after Galgame; existing presentation-surface tests pass |

PNG visual checks use the repository's local Meguri portrait. Live2D visual checks use the existing local Haru fixture, render visible canvas pixels, and assert the same canvas instance and dimensions across bubble changes: 350x540 CSS pixels, 700x1080 canvas pixels. Live2DCharacter.vue:70 compares individual preference fields to avoid reloading on chat changes.

## Checks

- `pnpm --filter @meguri/desktop-meguri test:visual-bubbles`: PASS. Actual native controller in an independent Electron process with temporary user data, preview todos and blocked external requests; no production writes.
- Focused layout, UI contract, preference and presentation-surface tests: PASS, 19 tests across 4 files.
- Full desktop unit suite: 307/308 PASS. The remaining failure is documented below.
- `pnpm --filter @meguri/desktop-meguri typecheck`: PASS.
- `pnpm --filter @meguri/desktop-meguri build`: PASS. Existing large-bundle warning remains.
- Preload, reminder-instance and tray smoke checks: PASS.
- Strict OpenSpec validation and scoped `git diff --check`: PASS.

The obsolete CSS snapshots for the compressed leg-overlay chat and fixed window-corner controls were retired. Actual geometry and interaction checks now exercise the replacement bubble layout; existing palette tests retain readability coverage.

## Findings

No critical findings or missing bubble behavior.

One unrelated warning: `apps/desktop-meguri/src/renderer/services/reminder-flow.test.ts:33` expects `clarify` for `明天3点提醒我开会`; the existing reminder parser returns `review`. This change does not alter ReminderFlow or reminder-intent parsing. The full suite is therefore not reported as fully passing.

## Artifacts and delivery

Visual results are saved in `.codex-runtime/bubble-layout-smoke/result.json`, alongside `compact.png`, `expanded.png`, `todo.png`, `reminder.png`, `attachments.png`, `skills.png`, `work.png`, `narrow.png`, `live2d-compact.png` and `live2d-expanded.png`.

The production renderer is rebuilt. Restart Meguri to load the updated native window controller and migrate old expanded sizes. The existing production process was not restarted during verification. Backend Todo writes and the user's specific Live2D model were not exercised by this isolated presentation check. Existing unrelated workspace changes remain; this change is not archived.
