## Why

Meguri currently treats spoken and typed reminder requests as ordinary chat drafts. Users need an explicit, reliable path from natural-language time and intent to a reviewed TimePrism Todo, without invented creation, execution, offline, or delivery claims.

## What Changes

- Share a bounded Chinese time/intent parser between typed messages and editable voice transcripts; resolve relative dates against Asia/Shanghai and ask for missing or ambiguous fields.
- Preview title, exact time, timezone, reminder/action type and desktop availability before an explicit confirmation.
- Persist account-bound creation receipts and reconcile uncertain writes instead of blindly repeating POST; validate the server's returned reminder fields before showing success.
- Support cancellation and time/title edits through the Todo panel, keeping server acknowledgement and reminder snapshots consistent.
- Permit only the `reminder` capability. Order checks, shell, computer control, external message sending, recurrence, and always-on scheduling are unsupported and cannot be confirmed as arranged.
- Make reminder parsing timezone-independent and suppress stale snapshots after network failure; preserve occurrence deduplication and existing desktop voice/QQ separation.
- Verify production capabilities read-only, report limitations separately from mock verification, and document deployment prerequisites.

## Capabilities

### New Capabilities
- `natural-language-reminders`: Intent parsing, clarification, confirmed writes, safe retry, edits/cancellation, bounded local delivery, and truthful support/delivery status.

### Modified Capabilities

None. The main `openspec/specs` directory contains no existing capability files; related historical changes remain untouched.

## Impact

Native Electron main/preload contracts, chat dock and Todo panel, TimePrism client, local reminder runtime, tests and user documentation. No new dependencies or changes to Shizuki/Core/AstrBot deployment, other projects, committed history, credentials or user data. Existing desktop reminder/voice/outbox mechanisms remain the delivery path. A server scheduler/worker is required before future offline scheduling or action execution can be enabled.
