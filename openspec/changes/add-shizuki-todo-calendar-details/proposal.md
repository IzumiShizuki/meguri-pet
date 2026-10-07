## Why

The companion's authenticated Shizuki Todo panel already accesses the site's TimePrism records but only shows a pending list. Users need calendar date details that make task names, full deadlines, and completion states visible, matching the site's new calendar entry points.

## What Changes

- Add a Todo calendar to the connected Shizuki panel, with month navigation, date markers, and complete date details.
- Reuse the panel's existing Todo snapshot and bridge so deadline edits and completion immediately update calendar details.
- Respect calendar visibility, day/minute precision, multi-day ranges, and Asia/Shanghai timezone.
- Guard refresh races and account transitions so an old account's records cannot reappear.

## Capabilities

### New Capabilities

- `desktop-todo-calendar-details`: Calendar and date details for the companion's existing TimePrism Todo connection.

### Modified Capabilities

None.

## Impact

Desktop TodoPanel, a reusable TodoCalendar component and calendar utility/tests. No IPC, server, credential, or reminder write contract changes. The related site change is `unify-calendar-deadline-details` in `D:/program/shizuki-site`.
