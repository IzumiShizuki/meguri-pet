# Meguri Harness Runtime 20

Status note (2026-07-29): this file describes the runtime seam and production
gates. The canonical requirement-by-requirement status is maintained in
`docs/notion-20-implementation-plan-2026-07-28.md`; it currently classifies
20.0-20.7 as partially implemented.

This document records the implementation boundary derived from the 19.x
current-state documents and the 20.x strengthened design documents. It
distinguishes three different claims: code implemented locally, behavior
covered by local tests, and production behavior proven against real
infrastructure. A local implementation is not production evidence.

## Selected interface

The external seam is intentionally small:

```java
public interface TurnRuntime {
    Mono<TurnSnapshot> submit(TurnCommand command);
    Flux<EventEnvelope> events(EventCursor cursor);
    Mono<TurnSnapshot> snapshot(String turnId);
}
```

`TurnCommand` is a sealed start/cancel command. AIRI, AstrBot, Website and the synchronous compatibility endpoint all use the same Turn lifecycle. Health, Persona overrides and other operator actions remain on a separate `HarnessControlPlane`.

Three designs were compared before implementation:

- A minimal three-entry Turn Runtime gives normal callers the highest leverage and keeps recovery rules local.
- A phase-graph framework gives module authors maximum flexibility but exposes too much policy to clients.
- A bound one-call client runtime best hides create/replay/reconnect for UI callers, and can be built over the same protocol later.

The selected hybrid uses the minimal Turn Runtime externally and typed internal Context, Persona, Capability and journal modules. Registry flexibility does not leak into client adapters.

## Implemented locally

### Companion Context

- Raw messages are immutable `MessageNode` values linked by `parentMessageId`.
- Each session has an `activeLeafMessageId`; selecting a sampled answer creates a new branch instead of overwriting the original node.
- `QUOTE`, `RESUME_FROM` and `TOPIC_LINK` are typed references.
- Topic merge preserves the candidate graph and projects it onto the parent branch.
- Derived summaries are separate versioned records with source message IDs,
  context revision and a source revision digest. Editing, deleting or replacing
  a source branch marks them `STALE`; stale summaries are never assembled into
  the model context.
- Graph snapshots expose all nodes, the active path, references, summaries and revision.
- `SessionContextPersistence` keeps storage outside the graph model. The
  `postgres` profile persists and restores the complete graph snapshot by
  user, client and session.
- `OpenAiProviderTokenizer` supplies provider-model token counting, and
  `GlobalPromptBudget` applies one budget to the system prompt and all context
  lanes.

### Persona Runtime

- Persona resolution is behind `PersonaRuntime` rather than exposed as a mutable state machine to the HTTP controller.
- Every Turn freezes a Persona revision and field-level provenance before execution.
- Verified adapter identity, temporal state, explicit turn input and client capability policy are recorded as separate sources.
- Temporal and outfit transitions are isolated by user and client. Debounce,
  cooldown and hysteresis cover boundary transitions instead of allowing
  rapid client-visible state flapping.

### Turn Runtime

- `TurnJournal` owns acceptance, scoped idempotency, request hashing, event sequences and replay.
- Reusing an idempotency key with a different payload is a conflict instead of silently returning the wrong Turn.
- Each Turn freezes one `HarnessManifest` containing Context, Persona, Capability, protocol and build revisions.
- Stages are `created`, `planning`, `retrieving`, `generating`, `finalizing` and one terminal stage.
- Retrieval lanes run concurrently with a shared absolute Turn deadline and per-capability timeouts.
- A structured-only provider emits one complete delta; the runtime no longer simulates token streaming with 18-character chunks.
- The synchronous chat endpoint submits and observes this same lifecycle instead of running a second orchestration pipeline.
- `PostgresTurnJournal` persists Turn snapshots, events, scoped idempotency,
  payload hashes, session sequences and outbox rows. Event and outbox writes
  share one transaction.
- PostgreSQL recovery restores completed snapshots and replayable events.
  Turns interrupted by a process restart become explicit failed terminal
  records rather than appearing to continue in memory.
- Each live SSE subscriber has a bounded 256-event buffer.

### Capability Runtime

- Descriptors distinguish `PROMPT_SKILL`, `RESOURCE`, `READ_TOOL`, `WRITE_TOOL` and `REMOTE_AGENT`.
- Every descriptor declares effect, approval policy, timeout, concurrency limit and implementation.
- Registration is separate from authorization. A sorted, versioned registry snapshot is frozen for each Turn.
- Retrieval and Memory lane timeouts are read from the frozen snapshot, so a hot registry change cannot alter an in-flight Turn.
- `CapabilityPolicy` and `CapabilityExecutor` enforce frozen grants, input
  schema checks, concurrency bounds, deadlines and risk-based write approval.
- Lore, Memory, Web and Weather calls pass through the executor instead of
  bypassing policy. Formal Memory uses the authenticated
  `formal_memory_allowed` grant.
- Retrieval mode is frozen as `NONE`, `FAST` or `SLOW`. `NONE` skips all
  retrieval lanes; `FAST` permits only Lore and Memory and is re-enforced by
  capability policy so Web, Weather and Remote Agent cannot be smuggled in via
  operation input or a stale grant.
- MCP descriptors and input schemas are normalized before registration.
  Remote Agent descriptors receive a bounded policy budget; this is not an
  operating-system sandbox.
- Execution returns content-free Effect Receipts. A durable Effect Ledger is
  still a production gate.

### Retrieval Runtime

- Java produces one `RetrievalBundle` with fixed Lore, Memory, Knowledge Base
  and Web lanes.
- The Python DashScope Lore path combines vector, keyword and rerank rankings
  using reciprocal rank fusion.
- Optional `retrieval.completed` events and traces record provider, status,
  result count and content SHA-256 without writing retrieved text to the
  journal.
- Lore errors and timeouts become an `unavailable` lane with no items. They do
  not fail the Turn or prevent the base LLM response.

### Adapter Protocol v1

- Envelopes contain `protocol_version`, stable `event_id`, `required`, session sequence and metadata.
- Replay returns the same event IDs.
- TypeScript accepts unknown optional events and advances its sequence checkpoint; unknown required events fail explicitly.
- Java, TypeScript and Python share the v1 envelope fields.
- The Controller delegates SSE replay to `TurnRuntime`; it no longer owns a duplicate polling loop.
- Website persists a v2 reducer/active-Turn checkpoint before page side effects
  and migrates v1 records. AstrBot stores the same replay cursor and reply
  assembly state in an atomic JSON checkpoint keyed by session and Turn, so a
  plugin restart can resume a previously accepted idempotent Turn.

### Formal Memory safety

- Direct and compatibility supersede calls create a version-bound candidate;
  they do not mutate a canonical item before explicit approval.
- Legacy upsert and the Java/Python bridge fail closed when an authoritative
  candidate workflow is unavailable.
- Prohibited/L0 candidates are replaced by a SHA-256 fingerprint, a fixed
  placeholder and a content-free rejection reason before the repository sees
  them. Rejected candidates cannot later be approved.
- Candidate models reject protected relationship fields recursively, while the
  repository hard-codes a new relationship stage to `null` and preserves the
  previous stage during an approved supersede.

## Production gates still open

The following work is still required before claiming production Harness 20
completion:

1. Prove the PostgreSQL Turn and Context implementations against a real
   database or Testcontainers, including restart, concurrency, unavailable
   database and transaction-failure scenarios.
2. Implement the Turn outbox dispatcher, delivery acknowledgement, retry and
   idempotent consumer contract. Cursor expiry and session snapshots now exist,
   but event retention/compaction and real PostgreSQL replay-gap evidence remain.
3. Add a background summary/precompression job. The current global token
   budget can synchronously remove optional records, but does not schedule
   durable summarization work.
4. Complete Memory file projection and true three-way conflict resolution.
   Prove Memory outbox delivery and recovery against PostgreSQL.
5. Route Lore, Memory, Knowledge, Graph and Web through one typed Planner,
   Bundle and Trace. Replace Memory's heterogeneous linear score fusion with
   RRF or a validated calibration model, and complete Web search/extract safety.
6. Persist full Persona profile, relationship, scene and interaction state,
   then define cross-client synchronization rules.
7. Connect Prompt Skills to Context assembly, provide a real Remote Agent/A2A
   transport, migrate remaining direct Gateways into Capability Runtime, define
   compensation semantics, and prove process/network isolation where required.
8. Add authenticated AIRI, AstrBot and Website E2E tests against the same Core
   and Relay, including expired tokens, forged identities, reconnect and
   durable replay.
9. Wire native AIRI Live2D only if it is selected as a delivery requirement.
   The current PNG-first implementation remains an explicit integration stage.
10. Generate Java, TypeScript and Python protocol models from one schema,
    align Tool/Approval/Skill/Agent required event catalogs, and make client
    idempotency keys stable across request retries.

Until these gates close, the correct status is: durable components implemented
and locally contract-tested, but production Harness 20 is not yet proven.

## Latest verification snapshot

- Java 21: 287 tests executed, 0 failures, 0 errors, 1 environment-dependent
  test skipped.
- Python: 342 tests passed; 8 environment/real PostgreSQL tests skipped.
- Root TypeScript protocol and adapters: 42/42 passed.
- AIRI Meguri Adapter: 22/22 passed; targeted strict TypeScript and ESLint passed.
- AIRI Stage: 405 tests passed; 4 existing Windows/upstream environment failures
  remain outside the Meguri adapter change set.
- No real PostgreSQL or authenticated cross-client E2E evidence is available
  on this workstation.
