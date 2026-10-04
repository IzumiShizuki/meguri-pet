## Why

Meguri currently has no safe, user-managed path for discovering and using the growing public Skills catalog on ModelScope. The integration must reuse Meguri's existing Agent and Capability authority so third-party procedural knowledge cannot create a parallel executor or elevate permissions.

## What Changes

- Add a generic compile-time `SkillSourcePlugin` extension and a public ModelScope implementation for search, detail, fetch, and update checks.
- Add an immutable, digest-addressed external Skill catalog with quarantine, validation, activation, update, rollback, compatibility, and audit lifecycles.
- Expose only bounded, read-only `meguri.skill.search` and `meguri.skill.view` capabilities to server-authorized AGENT turns, with deterministic frozen selection and progressive disclosure.
- Add internal management APIs protected by `skill:read` and `skill:manage` scopes.
- Add a native desktop Skills workspace for ModelScope search, import, validation, enablement, updates, and historical revision activation.
- Add a Python download bridge using `modelscope-hub>=0.2,<0.3`; do not install `ms-agent`, execute downloaded scripts, auto-install dependencies, or hot-load arbitrary JARs.
- Keep the ModelScope source disabled by default and preserve locally activated revisions when the remote source or bridge is unavailable.

## Capabilities

### New Capabilities

- `external-skill-catalog`: Immutable external Skill manifests, revisions, validation, compatibility, lifecycle, audit, update, and rollback.
- `modelscope-skill-source`: Public ModelScope catalog discovery and package fetch through a constrained source plugin and Python bridge.
- `agent-skill-disclosure`: AGENT-only deterministic frozen selection, bounded Skill search/view, untrusted context disclosure, and approval-resume continuity.
- `desktop-skill-management`: Scoped internal APIs and native desktop workflows for browsing and managing external Skills.

### Modified Capabilities

<!-- No archived main capabilities exist in openspec/specs yet. -->

## Impact

- Java Core capability catalog, Turn orchestration, persistence, security validation, audit, configuration, controllers, and PostgreSQL schema.
- Python Core internal bridge and Python dependency metadata.
- `apps/desktop-meguri` renderer navigation, API client, state, UI, and tests.
- New external data-root layout for immutable Skill packages and quarantined staging data.
- Optional network E2E against the public ModelScope Skill `@MiniMax-AI/minimax-pdf`; default CI remains fixture-based and offline.
