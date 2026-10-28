## Why

AstrBot currently delivers reminder occurrences that already exist in TimePrism, but it cannot create them from chat. The owner should be able to ask for a reminder naturally, review or edit the proposed time, and explicitly confirm a write to the same TimePrism account.

## What Changes

- Recognize owner-only private-chat reminder requests and prepare an editable draft using Asia/Shanghai time defaults.
- Add explicit confirm, edit, and cancel actions; no external write occurs while parsing or previewing.
- Add an authenticated Java Core endpoint that writes confirmed reminders to TimePrism, keeping Shizuki credentials in external Core secret files rather than AstrBot.
- Persist request receipts and reconcile ambiguous outcomes before retrying, preventing duplicate Todo creation.

## Capabilities

### New Capabilities

- `astrbot-timeprism-todo-creation`: Owner-authorized reminder drafting, confirmation, and idempotent TimePrism writes initiated from AstrBot chat.

### Modified Capabilities

None.

## Impact

The AstrBot gateway, its configuration and documentation, the Java Core reminder API and server-side TimePrism writer, and Core deployment configuration for external credential file paths. Existing reminder delivery remains in place. This change does not install or reconfigure a live AstrBot instance.
