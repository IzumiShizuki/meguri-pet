## 1. Contract and bridge

- [x] 1.1 Add scoped current-version display projection and test metadata exclusion/ownership.
- [x] 1.2 Add bounded correction submission through authoritative supersede candidates with stable request identity.
- [x] 1.3 Verify real candidate approval, duplicate retry and stale-version behavior using isolated service fixtures.

## 2. Core boundary

- [x] 2.1 Add authenticated detail/correction routes with strict body and request-ID validation.
- [x] 2.2 Exercise valid forwarding, unauthorized requests and malformed/overlong/unknown fields through the HTTP boundary.
- [x] 2.3 Extend only the two gateway memory-route patterns and verify real proxy forwarding with credential injection and method/path rejection against an isolated Core fixture.

## 3. Desktop

- [x] 3.1 Add typed API contracts and recoverable inline detail/correction editor.
- [x] 3.2 Bind editing to the selected session/memory, expose pending correction review and preserve drafts on errors.
- [x] 3.3 Test keyboard workflow, timeout retries, version conflict and session-switch races; inspect normal and narrow layouts.
- [x] 3.4 Advertise capabilities through bridge/Core status and hide new operations against older runtimes; test both status versions.

## 4. Verification and delivery

- [x] 4.1 Run relevant Python/Core/desktop regression tests, typecheck and production builds.
- [x] 4.2 Verify requirements against implementation, document evidence and runtime rollout requirements, and validate OpenSpec strictly.
