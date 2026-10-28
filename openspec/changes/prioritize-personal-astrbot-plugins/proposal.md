## Why

Meguri's all-private-message gateway currently captures messages handled by AstrBot plugins through regular expressions, Bilibili links/cards, and plain-text aliases. The owner wants existing plugins to take priority in private conversations and an accurate command catalogue based on the installed versions.

## What Changes

- Audit installed AstrBot plugin commands, aliases, private/group restrictions, and automatic triggers using a read-only server snapshot.
- Discover enabled handlers and release matching plugin commands and regular-expression triggers before calling Core, preserving explicit `/meguri` ownership.
- Preserve the existing configurable plain-text command release list and support AstrBot's configured command prefix and whitespace boundaries.
- Add targeted gateway regressions and a Chinese command/support catalogue, with configuration requirements and group-only functions identified.
- Prepare and verify the gateway package and a narrowly scoped server configuration update; retain rollback evidence for live changes.

## Capabilities

### New Capabilities

- `astrbot-personal-plugin-priority`: Existing enabled plugins own matching private-message commands and automatic triggers ahead of Meguri Core, with a documented installed-plugin catalogue.

### Modified Capabilities

None.

## Impact

The AstrBot gateway route policy, entrypoint/config schema, tests, and adapter documentation. No Core protocol or desktop behaviour changes. Live changes, if applicable to the selected entrance, affect only gateway routing/configuration and preserve other plugin data and permissions.
