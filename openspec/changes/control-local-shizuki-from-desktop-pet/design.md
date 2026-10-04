## Context

See `proposal.md` for motivation and the two capability specs for observable behavior. Shizuki's `codex/integrate-meguri-pet-frontend` branch publishes `%APPDATA%\Shizuki\control-api.json` with a process-lifetime bearer token and an ephemeral `127.0.0.1` endpoint. Its protocol `1.0` provides health, capabilities, state, commands, and SSE events. Meguri already has a Core-owned Capability Runtime and bounded ReAct loop, while both desktop renderers are intentionally unable to read arbitrary local files or secrets.

The current capability taxonomy assumes read-only tools or approval-gated durable writes. Shizuki navigation, window changes, and playback actions are externally visible side effects, but they are reversible local interactions explicitly requested in the current utterance. Treating them as reads is inaccurate; treating them as durable writes makes them unusable in the current ReAct approval flow.

## Goals / Non-Goals

**Goals:**

- Keep discovery credentials and HTTP transport inside Meguri Core.
- Preserve Shizuki's semantic allowlist and make every model-visible operation self-describing through a stable capability ID.
- Make local application authority opt-in through server configuration and scope.
- Return bounded, model-safe observations for success, offline, incompatibility, and renderer failures.
- Keep ordinary conversational turns on their current execution path.

**Non-Goals:**

- Start, install, update, or kill the Shizuki process.
- Consume the SSE stream in the first client version; state is read on demand and command responses are already correlated.
- Control the remote `shizuki-site` web deployment or its middleware.
- Expose arbitrary Shizuki commands, URLs, DOM operations, shell, JavaScript, or files.
- Change Shizuki's `1.0` API implementation in this repository.

## Decisions

### 1. Discover and call Shizuki only from Meguri Core

The adapter will resolve a configured manifest path, falling back to the current user's application-data directory. It will use a redirect-disabled HTTP client and will validate the manifest before building any authenticated request: bounded file size, protocol major `1`, live positive PID, non-empty bounded token, and a literal `http://127.0.0.1:<port>` origin without user info, query, fragment, or application path.

The client performs a health and capability handshake before commands. It rereads discovery for each top-level operation so a PID/token rotation is observed without a long-lived secret cache. A `401` can be retried once after rediscovery because Shizuki guarantees authentication failure occurs before dispatch; connection errors and command timeouts are not automatically retried because their outcome can be ambiguous.

Alternative considered: read the manifest in Electron main and forward commands over Meguri IPC. Rejected because the Agent tool executes in Core, forwarding would create another secret-bearing protocol, and the renderer must remain unable to access the token.

### 2. Register explicit capabilities instead of one generic command tool

The integration will expose one state read plus individual no-argument capabilities for the eight destinations, five native window actions, and five music actions. Each implementation maps to a compile-time command/payload pair and verifies that the command is in `/v1/capabilities` before POSTing. This costs more descriptors but gives the planner enough meaning because the current planner projection carries IDs and schemas rather than free-form descriptions.

Alternative considered: expose `{command, payload}` or `{action}`. Rejected because the current schema type system cannot express command-dependent enums and the model would receive a broader surface than the Shizuki contract recommends.

### 3. Add `LOCAL_CONTROL_TOOL` as a policy-level capability kind

`LOCAL_CONTROL_TOOL` represents reversible interaction with a trusted same-machine application. Action validation accepts it only with `EXTERNAL` side effect, `NONE` approval, denied network policy, at least one scope, and no idempotency requirement. The existing `WRITE_TOOL` approval and idempotency rules remain unchanged. Shizuki descriptors require `shizuki:control`, are permitted only in `DEEP` mode, and trust only locally normalized results.

The request utterance is the immediate authorization to perform the local interaction; the server-owned scope is the administrative authorization to expose it at all. This two-part gate is stronger and more usable than misclassifying controls as reads or globally weakening write approval.

Alternative considered: modify all write tools to permit `approval=NONE`. Rejected because that would weaken durable memory/document and future external-write policy. Adding a one-off exception keyed by `shizuki.*` was also rejected because policy should be structural rather than string-based.

### 4. Keep state reads separate from local controls

`shizuki.state.read` remains a normal scoped `READ_TOOL` with `READ` side effect. Command capabilities are local controls. The adapter returns a `summary` plus normalized `state` or `result`; the ReAct result projection uses that summary so a successful command is visible in the next planning round. Secret-key filtering remains a second line of defense, but the adapter never places the token in its result map.

### 5. Opt in from the trusted local launcher and route named intent

The Spring integration is disabled by default. `start-meguri-core.ps1` enables it and adds `shizuki:control` to `MEGURI_CAPABILITY_SCOPES`, which the existing identity verifier binds from server policy rather than the request body. A small deterministic intent detector recognizes the Shizuki name and common Chinese/English local-control phrasing, marking those requests tool-required. This selects AGENT only when the existing availability, client capability, budget, and deadline gates also pass.

Alternative considered: send `execution_mode=AGENT` for every desktop message. Rejected because it would add latency and tool exposure to ordinary companionship turns.

## Risks / Trade-offs

- [Risk] Nineteen explicit descriptors enlarge the planner context. → Restrict them to `DEEP` mode and the `shizuki:control` scope; the clarity is preferable to one unsafe free-form tool.
- [Risk] Intent wording may miss an unusual nickname or falsely classify a message containing “Shizuki”. → Keep matching narrow, allow the existing explicit `execution_mode=AGENT` override, and cover representative Chinese/English phrases with tests.
- [Risk] The Shizuki process can restart between handshake and command. → Rediscover after `401`, never retry ambiguous timeout/connection outcomes, and return a bounded offline/stale-session result.
- [Risk] Same-user malware can read the manifest. → This trust boundary already belongs to Shizuki `1.0`; Meguri adds strict loopback validation and never forwards the token.
- [Risk] SSE is not consumed, so unsolicited state changes are not pushed into Meguri. → Read state on demand; add resumable events later without changing command capability IDs.

## Migration Plan

1. Add the new capability kind and validation tests without changing existing write/read behavior.
2. Add the Shizuki client, explicit capability pack, intent detector, configuration, and focused tests.
3. Opt in only in the local Core launcher and document how to run both applications.
4. Run the Meguri Core test suite, Shizuki desktop protocol tests, and a live local smoke operation when the Shizuki desktop host is available.

Rollback removes the Shizuki configuration/registration and local launcher scope. `LOCAL_CONTROL_TOOL` can remain unused without affecting existing descriptors; if fully reverted, its enum/validator cases can be removed after confirming no catalog has persisted that kind.
