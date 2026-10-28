## Why

The owner cannot use Todo reminders by filling the visible AstrBot fields alone: reminder routes require global Core authentication even though production chat deliberately uses the legacy policy. The existing verified creation operator and private conversation can supply the owner and recipient without asking the user to invent internal identifiers.

## What Changes

- Authenticate reminder upload and feed routes independently of the ordinary chat policy, preserving exact owner, client, tenant and session checks.
- Configure the current verified owner's private reminder target with a rollback copy, preserving unrelated plugin settings.
- Verify reminder access and the poller without sending unsolicited test messages or creating real test Todos.
- Place connection URLs, credential paths and low-level polling/storage parameters in a collapsed advanced settings group, automatically migrating existing flat values without changing their effective behavior.
- Document what existing settings mean, the desktop dependency of the current reminder producer, and a proposed website-owned model for multiple verified QQ/WeChat bindings, per-binding permissions and per-Todo recipients. The multi-account model is a design deliverable, not an implemented feature in this change.

## Capabilities

### New Capabilities

- `owner-reminder-configuration`: Independently authenticated reminder routes and verified-owner delivery configuration.

### Modified Capabilities

None.

## Impact

TimePrismReminderController, focused Java/Python tests, a small gateway settings migration helper and schema grouping, existing server Core deployment and AstrBot configuration, and operator/design documentation. No website code, database schema changes, new dependencies or new identity bindings. Preserve existing uncommitted work and apply only the narrow settings integration to deployed plugin source.
