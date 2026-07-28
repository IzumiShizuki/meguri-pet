# Open Items

## AIRI Spike

- `apps/desktop-airi` is still an integration spike / diagnostic surface, not the final AIRI desktop fork.
- Remaining work is the native AIRI renderer handoff, especially the future `CharacterRenderer` -> `@proj-airi/stage-ui-live2d` integration.
- The current PNG-first stage, loopback support gateway, and artifact feed are intentional stopgaps.

## AstrBot Gateway

- The AstrBot gateway/plugin work is structurally complete for routing,
  rendering, remote-command preview/confirmation and local contract tests.
- Formal memory access is now fail-closed: an unbound platform identity sends
  `formal_memory_allowed=false`; only an explicit identity binding may request
  the authenticated permission header.
- No explicit code stubs or TODO placeholders were found in the gateway path;
  the remaining dependency is the separately configured Meguri Relay for
  remote commands.

## Notion 20 Audit

- The 2026-07-29 full re-audit classifies every 20.x page as partially
  implemented. The canonical Chinese matrix and evidence are in
  `docs/notion-20-implementation-plan-2026-07-28.md`.
- `20.1`: finish typed ContextBundle, online summary/rehydration, build trace and
  durable precompression.
- `20.2`: finish merge policies, three-way conflicts, last stable branches,
  tombstone projections and the local file mirror/repair loop.
- `20.3`: Knowledge/Graph is implemented; finish one typed runtime across Lore,
  Memory, Knowledge, Graph and Web, including Memory RRF and Web extraction.
- `20.4`: build authoritative Profile/Relationship/Scene repositories, the
  three-stage Persona reducer, PromptPolicyComposer and Persona evaluation.
- `20.5`: connect native provider streaming, dispatch Turn outbox records, add
  stable restart codes/retry lineage and run PostgreSQL fault injection.
- `20.6`: connect Prompt Skills, add a real Remote Agent transport, migrate the
  remaining direct Gateways and validate a real MCP server.
- `20.7`: align required event catalogs, use retry-stable idempotency keys, run
  one fixture suite across all adapters and complete authenticated cross-client E2E.

## Latest verification

- Java 21: 287 tests executed, 0 failures, 0 errors, 1 skipped.
- Python: 342 passed, 8 skipped.
- Root TypeScript: 42/42 passed.
- AIRI Meguri Adapter: 22/22 passed; strict TypeScript and targeted ESLint passed.
- AIRI Stage: 405 tests passed; 4 existing Windows/upstream failures remain.
- Both repositories were clean after commits `a594069` and `ab80ed8e`; neither
  branch has been pushed by this audit.
- Do not label PostgreSQL, cross-client authentication or Remote Agent isolation
  as production-complete without environment-backed evidence.
- The old `docs/notion-20-audit-2026-07-28.md` is retained as a historical snapshot.

## Review Note

- `pass` statements I checked are normal exception/abstract-class paths, not unfinished logic.
- No newly added TODO/FIXME markers were found in the Notion 20 implementation.
- `ops/env/*.local.env` and Vitest failure attachments are explicitly ignored so
  machine-local configuration and generated screenshots cannot enter commits.
