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

- `20.0/20.5`: the local Turn lifecycle now includes a PostgreSQL journal,
  transactional event/outbox writes, atomic session sequences, restart
  recovery and bounded subscriber buffers. Still open: real PostgreSQL fault
  tests, outbox dispatch/ack/retry, cursor compaction and replay-gap snapshots.
- `20.1`: provider token counting, one global prompt budget and PostgreSQL
  Message DAG snapshots are implemented locally. Still open: durable background
  summary/precompression jobs and real database recovery evidence.
- `20.2`: candidate review, versions, risk level, merge policy, base version,
  optimistic concurrency and `CREATE_ONLY` are implemented. Direct supersede,
  legacy upsert and the runtime bridge now fail closed into candidate review;
  L0 payloads are redacted before the first repository write, and ordinary
  candidates cannot mutate protected relationship fields. Still open: file
  projection, true three-way conflict handling and production outbox evidence.
- `20.3`: Lore vector/keyword/rerank rankings now use RRF and Java emits one
  four-lane `RetrievalBundle`. Lore failure now degrades independently to an
  `unavailable` lane instead of failing the Turn. Still open: a real Knowledge Base provider,
  result-level scores/filter reasons and a complete query-to-answer trace.
- `20.4`: deterministic snapshots, provenance, override TTL and isolated
  temporal debounce/cooldown/hysteresis are implemented. Still open: complete
  profile/relationship/scene/interaction persistence and cross-client sync.
- `20.6`: policy, approval, schema validation, executor, concurrency/timeout,
  MCP normalization and bounded Remote Agent policy are wired into Turn
  execution. Still open: a PostgreSQL Effect Ledger, compensation/undo and a
  real OS/container sandbox.
- `20.7`: AIRI, AstrBot and Website share the v1 protocol with local reconnect,
  duplicate suppression, identity scoping and downgrade tests. Website and
  AstrBot now persist reducer checkpoints before side effects and restore them
  across page/plugin restarts. Cross-client authenticated E2E, Client Hello,
  cursor-expiry snapshots and durable server event replay remain open; AIRI
  native Live2D wiring is still a separate spike boundary.

## Latest verification

- Java 21: 139 passed, 1 environment-dependent test skipped.
- Python: 341 passed, 8 PostgreSQL tests skipped because
  `MEGURI_TEST_DATABASE_URL` is unavailable.
- TypeScript protocol/adapter/website: 25 passed; Desktop Node: 13 passed,
  1 symlink test skipped.
- AIRI targeted verification: 85 tests passed across Adapter, Tamagotchi and
  Stage UI; relevant ESLint and `core-agent` typecheck passed.
- AIRI full `stage-tamagotchi` typecheck remains blocked by the existing missing
  `apps/server` and `@proj-airi/server-runtime/server` module boundary.
- AIRI's full Tamagotchi suite also retains unrelated Windows baseline failures:
  symlink creation requires privileges, one path assertion assumes `/`, and an
  unbuilt `@proj-airi/electron-vueuse` package entry prevents one suite import.
- Both repositories pass `git diff --check`; the large worktrees still need to
  be split into reviewable commits.
- Do not label PostgreSQL, cross-client authentication or Remote Agent isolation
  as production-complete without environment-backed evidence.
- The requirement-by-requirement Chinese audit is recorded in
  `docs/notion-20-audit-2026-07-28.md`.

## Review Note

- `pass` statements I checked are normal exception/abstract-class paths, not unfinished logic.
- No newly added TODO/FIXME markers were found in the Notion 20 implementation.
- `ops/env/*.local.env` and Vitest failure attachments are explicitly ignored so
  machine-local configuration and generated screenshots cannot enter commits.
