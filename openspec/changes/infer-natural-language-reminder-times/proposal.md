## Why

The reminder parser currently asks for a date and AM/PM even when the nearest future time is a reasonable first draft. For example, at 00:00, “提醒我1点睡觉” should start with 01:00 today and let the user adjust it during review.

## What Changes

- Infer an omitted date as today in Asia/Shanghai, advancing only when the inferred time has already passed.
- Resolve a bare 1–12 hour to the nearest future AM/PM occurrence instead of asking which period the user means.
- Infer a missing clock deterministically: sleep-related reminders use 12:00, otherwise the next half-hour.
- Raise the Todo surface immediately and auto-confirm a connected, unambiguous one-shot reminder; preserve review/retry state when the write cannot be confirmed.

## Capabilities

### New Capabilities

- `natural-language-reminder-time-inference`: Defaults for missing dates and ambiguous bare hours in reminder drafts.

### Modified Capabilities

None.

## Impact

Update the shared desktop reminder parser, chat routing, and behavior specification. The existing safe write and retry flow remains in place; no API, dependency, AstrBot, or server changes are required.
