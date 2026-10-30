## Why

The audited DDG Skill requires URL fetching with preserved result links. Meguri currently has keyword search and an internal text extractor, neither of which satisfies that contract.

## What Changes

- Add a disabled-by-default, explicitly registered web_fetch read capability for public HTTP(S) documents.
- Validate and pin public DNS destinations for every redirect hop; bound requests and output without credentials, scripts or recursive crawling.
- Preserve link text and addresses as untrusted document data, with offline security and capability integration tests.
- Keep third-party packages uninstalled and running service configuration unchanged.

## Capabilities

### New Capabilities
- `public-web-fetch`: Bounded, credential-free public document reads through the existing capability permission and trust boundary.

### Modified Capabilities
None.

## Impact

Java capability and web fetch implementation, tests and documentation only. Reuse existing Reactor Netty and JDK HTML parser; no new dependencies, account systems or network-policy exceptions. No automatic Skill import/activation.
