## Verification Report: add-shizuki-todo-calendar-details

| Dimension | Result |
| --- | --- |
| Completeness | 5/5 tasks; 3/3 requirements implemented |
| Correctness | Timezone, precision, visibility, range, overflow, updates and account races covered |
| Coherence | Existing Todo bridge/snapshot reused; compact list/calendar views and inline details follow design |

### Requirement evidence

- Connected calendar: `todo-calendar.ts` projects existing bridge Todos and normalizes both naive and zoned dates to Asia/Shanghai. Five utility tests cover date validation, timezone rollover, hidden/undated/reversed ranges, inclusive ends, complete matching, precision/status, and year-boundary month navigation.
- Complete date details: `TodoCalendar.vue` renders 42 keyboard-operable date buttons, month controls, empty states, and every matching item with title, deadline/start, detail, and completed/overdue state. TodoPanel supplies the same confirmed snapshot through list/calendar view switching.
- Current account snapshot: TodoPanel sequences refreshes, verifies the account again after reading/writing, clears changed/disconnected account records, and refreshes on focus/visible-page intervals. Its confirmed creates/edits/completions update the supplied snapshot. A headless browser test completes a Todo through the existing list and verifies open date details, then resolves an old-account read after an account switch and verifies no stale record appears.

### Validation

- Targeted Vitest: calendar utilities, existing Shizuki client, write journal and reminders; four files, 33 tests passed.
- `pnpm typecheck`: passed.
- `pnpm build`: passed with existing bundle-size advisories.
- Edge headless UI: seven-item details, full deadline labels, list/calendar switching, completion updates, and account race passed without page errors. Screenshot layout inspected after preventing flex shrink overlap.
- `openspec validate add-shizuki-todo-calendar-details --type change --strict --no-interactive`: passed.
- `git diff --check` for calendar files: passed.

No critical issues or functional warnings. Temporary UI harnesses were removed. No bridge contract, dependency, reminder behavior, account credential, deployment, or installation change was required. The companion displays Todos; board tasks/schedules and public publication counts stay in the site's respective calendars.
