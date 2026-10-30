## Why

Imported Skill compatibility can become stale when dependencies change, disabled bindings are projected as active by digest alone, and known public-read failures lose their reason before reaching chat. Discovery and import must not imply runtime callability.

## What Changes

- Reuse current dependency checks for read projections, activation and new frozen-turn selection while preserving stored validation evidence and existing authorization.
- Distinguish public discovery, installed activation, missing dependencies and dependencies not exposed in the current turn. Treat actual approval rejections separately.
- Preserve bounded, enumerated failure reasons through Capability execution and ReAct to final chat context; keep raw exception/server content private.
- Add offline regressions and an acceptance guide; do not install/activate packages, change network policy, credentials, or the primary tree.
- Permit existing explicitly approved, scoped MCP reads through ReAct while retaining exact executor approval validation. Honor omitted MCP outputSchema as optional instead of imposing an empty-output schema; declared schemas remain strict.

## Capabilities

### New Capabilities
- `skill-availability-reporting`: Accurate Skill readiness and failure explanation using existing state and frozen capabilities.

### Modified Capabilities

## Impact

Java Skill management/selection/disclosure, capability error mapping, ReAct projection and narrow runtime wiring. No new state machine, database migration or protocol event type.
