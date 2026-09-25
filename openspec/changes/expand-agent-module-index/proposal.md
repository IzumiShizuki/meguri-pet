## Why

The first agent guide identifies the major directories and one canonical request path, but an agent still has to inspect many source files to locate the Python routes, Java package responsibilities, TypeScript adapters, desktop proxy, and operational entrypoints. A detailed module index and visual link map will make code navigation and safe change placement faster and more reliable.

## What Changes

- Add a detailed `.agent/module-index.md` covering the runtime modules, package responsibilities, important entrypoints, public routes, and verification surfaces.
- Extend `.agent/architecture.md` with Mermaid diagrams for request flow, event/replay flow, local startup dependencies, and authority boundaries.
- Add links from the agent entrypoint so future agents can choose the right level of detail without scanning the whole repository.
- Keep the documentation derived from the current source tree and point to code as the source of truth; do not change runtime behavior or API contracts.

## Capabilities

### New Capabilities

<!-- Documentation-only change; no runtime capability is introduced. -->

### Modified Capabilities

<!-- None. -->

## Impact

Only `.agent/` documentation and this OpenSpec change directory are affected. The change adds no dependencies, generated artifacts, credentials, external integrations, or runtime code.
