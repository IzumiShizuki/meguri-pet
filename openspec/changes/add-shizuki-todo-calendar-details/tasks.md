## 1. Calendar integration

- [x] 1.1 Implement/test Shanghai calendar projection, precision, visibility, inclusive ranges, complete date matching, and status.
- [x] 1.2 Add an accessible compact TodoCalendar and complete inline date details to TodoPanel.
- [x] 1.3 Guard account/refresh races and keep calendar snapshots current on confirmed writes, focus, and visible-page refresh.

## 2. Verification and handoff

- [x] 2.1 Run applicable calendar/bridge regression tests, renderer typecheck/build, and inspect rendered behavior.
- [x] 2.2 Verify spec/design coverage, run strict OpenSpec validation, record results, and commit only calendar work locally.

## Handoff

All five tasks are complete; see verification.md. In Shizuki TodoPanel choose “日历”, then a date to inspect every matching Todo and its deadline. Use the same Shizuki account as the website for shared records. No implementation work remains for this scope. Unrelated existing desktop/backend changes are excluded from this local commit. No push, installation, or deployment was performed.
