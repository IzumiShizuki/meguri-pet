## Why

Meguri already writes Todo items to the connected Shizuki TimePrism account and shows local reminder cards, but a due item does not reach the owner's QQ chat or trigger speech. The user wants both QQ proactive delivery and desktop voice for the main account.

## What Changes

- Pin reminder delivery to one explicitly selected Shizuki account ID, so changing the desktop login does not redirect another account's tasks to the owner's QQ chat.
- Speak due reminders through the existing local TTS path when desktop speech is enabled, while keeping the card and system notification.
- Add an idempotent reminder outbox from the trusted desktop host to the remote Meguri Core, and a durable Core reminder inbox for AstrBot polling.
- Add an owner-targeted AstrBot poller that proactively sends each reminder to one configured QQ private message origin and records successful deliveries.
- Surface QQ connection/configuration and delivery failures without claiming a message was sent when it was only queued.

## Capabilities

### New Capabilities

- `owner-timeprism-reminder-delivery`: Owner-bound desktop voice and reliable QQ delivery of Shizuki TimePrism Todo occurrences.

### Modified Capabilities

None.

## Impact

The native Electron app, Meguri Core HTTP API and persistent state, and the AstrBot gateway plugin change. Shizuki TimePrism remains the Todo source of truth. Remote Core and AstrBot deployment/configuration are required before QQ delivery becomes live; the local desktop voice path can operate independently.
