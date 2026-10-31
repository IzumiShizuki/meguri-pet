## Why
The user's tasks, agreements and notes must share the site's authority across website, pet and independent clients. Existing desktop writes do not expose conflict versions and cannot edit general task details or notes.

## What Changes
- Publish one v1 contract for all clients; keep nullable deadlines and existing task IDs.
- Add a capability-gated desktop center with tasks, agreements, urgency sorting and independent notes.
- Persist account-bound snapshots and immutable pending mutations, replaying the same request ID; show conflicts and offline state.
- Reuse existing login, button, window and reminder schedule boundaries.

## Capabilities
### New Capabilities
- `timeprism-desktop-center`: shared task/note UI and protected synchronization.
### Modified Capabilities
None. Existing reminder paths remain compatible.

## Impact
Desktop main/preload/shared contract and Todo panel; site implementation occurs in its own repository. No new credentials, unknown packages, production writes or deployment in implementation tests.
