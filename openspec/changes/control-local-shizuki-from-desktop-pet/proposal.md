## Why

Shizuki's `codex/integrate-meguri-pet-frontend` branch now exposes a versioned, authenticated loopback control API, but Meguri Pet has no client or model-callable capability that can use it. Connecting the two applications lets the user control the local Shizuki desktop application through natural-language requests to the pet without exposing a general shell, browser automation surface, or bearer token to the renderer or model.

## What Changes

- Add a trusted local Core adapter that discovers the current Shizuki process from its per-user manifest, validates the loopback endpoint and protocol, reads state/capabilities, and invokes only advertised semantic commands.
- Register explicit Shizuki state, navigation, window, and music capabilities for bounded ReAct turns; do not expose free-form command names, URLs, selectors, JavaScript, shell, or filesystem operations.
- Add a `LOCAL_CONTROL_TOOL` capability kind for reversible, scope-gated interaction with a local application. It can run without a second approval when the user's turn is already the explicit request, while durable writes remain approval-gated.
- Detect explicit Shizuki-control requests as tool-required turns and enable the integration only from the trusted local desktop launcher with the `shizuki:control` server-side scope.
- Document configuration, supported requests, offline behavior, and the requirement that the Shizuki desktop application be running.

## Capabilities

### New Capabilities

- `bounded-local-application-control`: Defines the policy boundary for direct, reversible local-application controls that are explicitly requested by the user and granted by server-side scope.
- `shizuki-desktop-control`: Discovers and safely operates the Shizuki `1.x` loopback control API through explicit state, navigation, window, and music tools.

### Modified Capabilities

None.

## Impact

- `java/meguri-core`: capability taxonomy/validation, Shizuki discovery and HTTP adapter, Spring registration, intent routing, and tests.
- `ops/scripts/start-meguri-core.ps1`: local-only integration enablement and `shizuki:control` scope grant.
- `java/meguri-core/src/main/resources`: environment-backed Shizuki settings.
- `docs`: local setup and supported-command documentation.
- External contract consumed: `%APPDATA%\Shizuki\control-api.json` and the Shizuki desktop control protocol `1.0`; no changes are required in `D:\program\shizuki-site`.
