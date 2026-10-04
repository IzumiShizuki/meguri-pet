## Purpose

Ensure the desktop renderer can establish browser-based SSE subscriptions through the loopback gateway using every request header emitted by the shared Meguri SDK.

## ADDED Requirements

### Requirement: SDK SSE headers are CORS-safe
The desktop gateway SHALL include `Meguri-Protocol-Version` and `Last-Event-ID` in `Access-Control-Allow-Headers` for an accepted desktop CORS preflight request.

#### Scenario: Browser preflights an SSE subscription
- **WHEN** an accepted desktop origin preflights a session events request that includes `Meguri-Protocol-Version` and `Last-Event-ID`
- **THEN** the gateway returns a successful preflight response whose allowed headers include both request headers

#### Scenario: Untrusted origin remains rejected
- **WHEN** an untrusted origin preflights the same request
- **THEN** the gateway rejects it without broadening desktop origin trust
