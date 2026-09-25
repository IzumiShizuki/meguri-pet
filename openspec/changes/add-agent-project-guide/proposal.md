## Why

Meguri is a polyglot workspace with Python, Java, TypeScript, Electron, adapters, local services, and operational scripts. A new agent currently has to reconstruct the repository map, safe defaults, toolchain paths, test commands, and authority boundaries from many separate documents, which increases startup time and the risk of using the wrong runtime or touching protected data.

## What Changes

- Add a checked-in `.agent/` entrypoint for agent onboarding and task handoff.
- Document the repository map, runtime ownership, common development and verification commands, Windows toolchain paths, and configuration boundaries.
- Document project invariants: offline/mock defaults, secret handling, authoritative Python memory boundaries, Java 21 requirements, adapter contracts, and OpenSpec/git workflow expectations.
- Provide a concise troubleshooting and pre-merge checklist that points agents to the existing source-of-truth files.
- Do not change runtime behavior, public API contracts, dependencies, or deployment state.

## Capabilities

### New Capabilities

<!-- This is a documentation/tooling-only change; no spec-level capability is introduced. -->

### Modified Capabilities

<!-- None. -->

## Impact

Only repository documentation is added under `.agent/` and this OpenSpec change directory. The guide references existing code, scripts, and configuration examples but does not read or embed secrets, modify generated artifacts, or alter build/runtime behavior.
