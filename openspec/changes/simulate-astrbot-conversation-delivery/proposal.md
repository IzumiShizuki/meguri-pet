## Why

Existing routing tests replace the Core or sender entirely, while production logs show completed replies can still promise unsupported actions. The user requests simulated AstrBot users with available ports, so conversation routing and actual HTTP outputs can be exercised without a QQ account.

## What Changes

- Add an isolated simulator using synthetic private/group events, system-assigned loopback ports and an HTTP delivery collector.
- Exercise the real plugin entrypoint, gateway, identity mapping, HTTP Core client and SSE handling against controlled protocol fixtures.
- Add a repeatable runner that can also use an explicitly configured loopback Core; record real responses separately from deterministic fixtures.
- Verify routing, identity separation, duplicates, reminders/confirmation/cancellation and recoverable failures; distinguish collector acceptance from QQ/device delivery.
- Fix the single-character Chinese ten parsing defect found by the end-to-end reminder simulation.
- Repair the same-session event replay defect reproduced against the configured Core: later turns must advance over prior turns without applying their output.

## Capabilities

### New Capabilities
- `astrbot-conversation-simulation`: repeatable private/group adapter tests with isolated identities, real local HTTP transport and auditable captured outputs.
- `astrbot-session-stream-consumption`: consume session-wide event history across successive turns while rejecting a different session and applying only the requested turn's data.

### Modified Capabilities

None. Production platform routing and permissions are not changed by this testing tool.

## Impact

New test support, Python regression tests, a local simulation CLI, documentation and narrowly scoped repairs to reminder numeral parsing and session-event consumption. Reuses installed Python/httpx and the existing AstrBot adapter. No QQ API calls, new external accounts, deployment or live configuration changes.
