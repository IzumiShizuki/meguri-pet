## 1. Isolated simulator

- [x] 1.1 Add a minimal SDK boundary and synthetic events using the actual plugin/gateway.
- [x] 1.2 Add a port-zero loopback protocol fixture and delivery collector with bounded cleanup.

## 2. Regression coverage

- [x] 2.1 Verify private/group destinations, identities, ownership, duplicate events and protocol/SSE requests.
- [x] 2.2 Verify retrieval outcomes, connection/collector failures and recoverable retries.
- [x] 2.3 Verify authorized reminder prepare/confirm/cancel, duplicate confirmation and group rejection using observed writes.
- [x] 2.4 Reproduce and repair prior-turn replay rejection on a session stream; retain sequence and cross-session protections and retest the configured Core.

## 3. Repeatability and acceptance

- [x] 3.1 Add a CLI with fixture/configured-Core modes, captured public outputs and explicit evidence labels.
- [x] 3.2 Run synthetic private/group probes through an existing Core with no platform sends, recording configuration and semantic findings.
- [x] 3.3 Run focused/full Python regressions, document usage/results and verify OpenSpec without archiving.
