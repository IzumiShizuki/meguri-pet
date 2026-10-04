## 1. Local Control Policy

- [x] 1.1 Add `LOCAL_CONTROL_TOOL` to the capability taxonomy with fail-closed descriptor invariants.
- [x] 1.2 Extend ReAct action validation and execution tests so scoped local controls run directly while durable write approval rules remain unchanged.

## 2. Shizuki Core Integration

- [x] 2.1 Implement strict manifest discovery, protocol/health/capability validation, authenticated state reads, and correlated command invocation.
- [x] 2.2 Register the scoped Shizuki state, destination, window, and music capabilities with model-safe result summaries.
- [x] 2.3 Add deterministic Shizuki-control intent detection so explicit requests enter the bounded AGENT path without affecting ordinary conversation.

## 3. Local Configuration and Documentation

- [x] 3.1 Add environment-backed Shizuki integration settings and opt in with `shizuki:control` only from the trusted local Core launcher.
- [x] 3.2 Document setup, supported natural-language operations, offline behavior, protocol boundaries, and troubleshooting.

## 4. Verification

- [x] 4.1 Add focused tests for discovery security, authentication, command allowlisting, scope gating, capability mapping, result redaction, and intent detection.
- [x] 4.2 Run OpenSpec validation, Meguri Core tests/build, relevant TypeScript tests, and Shizuki desktop protocol/smoke tests.
- [x] 4.3 Perform a live local state/command smoke test when the Shizuki desktop manifest is available, or record the exact external prerequisite if it is not running.
