## 1. Regression Coverage

- [x] 1.1 Add parser tests for null, omitted, valid-string, and invalid `required_extension` values
- [x] 1.2 Add an SSE framing regression that includes `required_extension: null`
- [x] 1.3 Add snapshot parser tests for omitted, null, string, and invalid turn `error` values
- [x] 1.4 Add a desktop gateway preflight test for the SDK's `Meguri-Protocol-Version` and `Last-Event-ID` headers

## 2. Protocol Compatibility Fix

- [x] 2.1 Normalize nullable `required_extension` values to optional absence at the shared event parser boundary
- [x] 2.2 Preserve existing extension support checks for present non-empty identifiers
- [x] 2.3 Normalize nullable snapshot turn errors while preserving strict present-value validation
- [x] 2.4 Allow the SDK SSE headers in trusted desktop CORS responses

## 3. Verification and Desktop Recovery

- [x] 3.1 Run protocol, SDK, and desktop test/typecheck/build validation
- [x] 3.2 Replay the affected persisted desktop session through the live gateway and confirm SDK event delivery
- [x] 3.3 Restart Meguri with the rebuilt renderer and confirm the desktop conversation no longer reports a connection failure
