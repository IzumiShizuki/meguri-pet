## Context

See proposal.md. TodoPanel already retrieves all account Todos through the Shizuki IPC bridge and updates its snapshot after mutations. The surrounding ChatDock and many unrelated desktop files have existing local changes. Calendar work will only touch the currently clean TodoPanel and add isolated calendar files.

## Goals / Non-Goals

**Goals:** Reuse the existing connected panel snapshot and make date details consistent with TimePrism.

**Non-Goals:** New account setup, IPC endpoints, task/schedule fetching, public article counts, reminders, or deployment.

## Decisions

- Add a self-contained TodoCalendar component receiving TodoPanel's array as a prop. Keep a pure calendar utility for Asia/Shanghai date normalization, valid visible entry projection, range matching, precise labels, and six-week grid generation. No second data store or requests are needed.
- Add list/calendar view controls and show complete date details inline below the compact month grid in the already scrollable Todo panel. Keep the calendar mounted when changing views so selected dates survive confirmed list edits. This fits the companion's compact window better than an additional desktop window or blocking modal.
- Sequence panel refreshes and verify the account again after listTodos. Clear entries before reporting a disconnected state or account transition. Refresh on focus and a visible-page interval, avoiding busy mutations. On successful writes invalidate older refreshes before updating the snapshot.

## Risks / Trade-offs

- [Host timezone differs from Shanghai] → Normalize zoned timestamps to Asia/Shanghai and preserve naive server dates as Shanghai wall time, using explicit timezone formatting.
- [Existing local work] → Avoid shared desktop contracts/styles and stage only the calendar files, TodoPanel, and this change's artifacts.

## Migration Plan

No data migration is required. Build the native renderer with the existing environment. Rollback reverts the TodoPanel integration and new calendar files. Unrelated local work remains intact.
