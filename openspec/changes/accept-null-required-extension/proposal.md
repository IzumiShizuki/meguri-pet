## Why

The desktop client currently rejects valid Core conversation responses that serialize optional fields as JSON `null`. Browser SSE is also blocked because the desktop gateway omits the SDK protocol header from its CORS allowlist, so a completed Meguri turn is incorrectly shown as “这次没有连接上 Meguri。”

## What Changes

- Accept `required_extension: null` as equivalent to an omitted optional extension identifier.
- Accept a successful snapshot turn whose optional `error` value is `null`.
- Preserve strict rejection for empty strings and other invalid non-null extension values.
- Allow the SDK's `Meguri-Protocol-Version` and `Last-Event-ID` SSE headers in desktop gateway CORS preflight responses.
- Add regression tests for nullable event/snapshot envelopes and browser SSE preflight, then verify the desktop renderer against the live gateway.

## Capabilities

### New Capabilities

- `protocol-event-compatibility`: Defines compatibility rules for optional nullable fields in Core turn-event and session-snapshot envelopes.
- `desktop-conversation-transport`: Defines browser CORS requirements for the desktop SDK's SSE transport.

### Modified Capabilities

None.

## Impact

- Affects the shared TypeScript protocol event/snapshot parsers, desktop gateway CORS policy, and their tests.
- Restores desktop conversation streaming through `@meguri/client-sdk` without changing the HTTP/SSE API or Core service.
- No breaking API or dependency changes.
