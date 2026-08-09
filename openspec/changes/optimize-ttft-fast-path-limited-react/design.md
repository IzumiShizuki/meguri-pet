## Context

See `proposal.md` for motivation. The codebase already has durable Turn/Event journals, an adapter protocol with monotonic replayable events, native Provider streaming, persona composition, parallel retrieval lanes, and capability snapshots. It does not have `TurnExecutionMode`, `TurnLatencyTrace`, the proposed read models, retrieval quorum completion, or an immediate-first-token delta policy. Existing `NativeTextDeltaAggregator` applies one time/size window to every chunk, and current retrieval joins every scheduled lane before bundle assembly.

The normative product sources are the Notion performance plan, its integration-contract page, and the current implementation architecture page. The performance plan is `FOLLOW-UP / PLANNED`; current code and tests remain the implementation truth until tasks here are verified.

## Goals / Non-Goals

**Goals:**

- Freeze cross-agent v1 names and wire fields before parallel implementation.
- Keep execution mode, retrieval mode, and capability authority separate.
- Make latency claims reproducible across releases and clients.
- Preserve durable event, deadline, replay, cancellation, idempotency, ACL, and trust invariants.

**Non-Goals:**

- Replacing the existing Turn/Capability/AgentTask architecture with a graph framework.
- Making caches or read models authoritative.
- Enabling Web, Graph, Remote Agent, or write tools by default.
- Choosing hard-coded latency, rerank, quorum, or coalescing thresholds before measurement.

## Decisions

### 1. One versioned wire contract, domain-owned Java types

`contracts/performance/v1/performance-contract.schema.json` is the canonical cross-language wire shape. Domain owners implement Java types in their existing/new domain packages; there is no second Java mirror package. Existing `TurnEventEnvelope` remains the adapter-protocol v1 `EventEnvelope`, and existing `RetrievalMode` wire values remain `NONE | FAST | SLOW`.

Alternative considered: let each branch invent local DTOs and reconcile later. Rejected because the repository already has duplicate retrieval types and the Notion integration plan explicitly requires interface freeze first.

### 2. Execution mode and retrieval mode are orthogonal

`TurnExecutionMode = FAST | THINK | AGENT` controls reasoning/action eligibility. `RetrievalMode = NONE | FAST | SLOW` controls retrieval depth. `ExecutionModeDecision` contains the selected mode, reason codes, a server-clipped budget, whether the user explicitly selected it, and whether ReAct is eligible.

Only the server assembles `CurrentTurnSignals`. A client may request an execution mode, but cannot submit trusted signals, budgets, or `react_eligible`.

### 3. Absolute deadline and subset budgets

Every execution/retrieval/agent budget carries the Turn's absolute `deadline_at`. Child work receives a subset of remaining calls, tokens, cost units, depth, and time. No model or Skill can expand those limits.

### 4. Read models are rebuildable projections

`PersonaBaseSnapshot` contains stable, version-keyed persona/relationship/preference state only. Current input, temporal computation, `SceneDelta`, runtime override, client capability, retrieval result, tool result, and exemplar selection remain per-turn inputs to `EffectivePersonaState`.

`ConversationContextReadModel` covers the active leaf/topic, latest stable summary, recent window, open loops, references, token count, and version. Quote/resume/topic-link/edit/branch-switch/stale-summary cases deterministically fall back to the complete harness.

### 5. Retrieval may finish at minimum evidence, never at minimum trust

Original-query lanes may start speculatively before rewrite/embedding. A versioned `EvidenceQuorum` may allow the provider path to proceed before all lanes settle, but only after required ACTIVE/trusted seats are satisfied. Late work is cancelled or recorded as degraded; ACL, tenant/user/scope, document version, and evidence provenance are unchanged.

### 6. First delta is a special durable boundary

The first non-empty Provider token becomes a `TextDeltaChunk` immediately, is appended as a Turn event, and is broadcast/flushed only after persistence succeeds. Only subsequent tokens use configurable time/character coalescing. Thresholds are configuration and benchmark outputs, not constants in this specification.

### 7. TTFT evidence precedes rollout claims

The same semantic boundaries define before and after traces. Reports include sample size and P50/P95/P99 grouped by release, model, provider, execution mode, retrieval mode, and client type. Until a valid baseline and comparison are produced, TTFT remains `NOT_MEASURED`; neither the proposal nor task completion may invent milliseconds or percentage gains.

### 8. Limited ReAct reuses existing authority

Only AGENT mode may enter the loop. Decisions are limited to `CONTINUE`, `FINALIZE`, `WAIT_FOR_APPROVAL`, `DELEGATE_AGENT`, and `FAIL`. Every action passes snapshot, policy, schema, approval, idempotency, budget, and deadline checks. Agent results re-enter as untrusted observations and cannot directly mutate persona, relationship, or formal memory.

### 9. Agent planning is a bounded control-plane call

The optional Agent planner SHALL run only after a server-authoritative `AGENT` execution decision; a Skill description, SLOW retrieval, or ordinary provider request cannot invoke it alone. Its provider client and concurrency budget are independent of ordinary response generation. The route defaults to a non-thinking, small-output configuration with a physical provider deadline shorter than the Turn-level planner deadline. The Planner MAY specify a distinct `MEGURI_AGENT_PLANNER_API_KEY_FILE`; when absent, it SHALL reuse the ordinary `MEGURI_LLM_API_KEY_FILE` credential for the same provider base URL. Inline Planner credentials are not supported. Queue saturation, provider deadline, upstream failure, and invalid structured output are emitted as distinct sanitized reason codes; a valid no-Agent decision remains a normal empty result. Planner failure remains fail-open only when no caller-required Agent action exists.

## Risks / Trade-offs

- [Cross-language drift] -> Validate all implementations against the same JSON fixtures and reject breaking field reuse within v1.
- [Read-model staleness] -> Bind snapshot keys to authority versions and keep deterministic full-harness fallback.
- [Early retrieval harms recall] -> Gate quorum policies with offline quality tests and a feature flag; retain all-settled fallback.
- [Coalescing breaks replay] -> Persist monotonic recoverable text chunks and compare replayed text/terminal state byte-for-byte.
- [Client timing is incomparable] -> Separate character TTFT from AstrBot's first-platform-message metric and label missing client render timestamps.
- [Dirty brownfield workspace] -> Limit this change to new OpenSpec, ADR, documentation, and contract files; do not modify unrelated existing work.

## Migration Plan

1. Land the v1 schema, fixtures, ADR, trace names, and optional fields without enabling behavior.
2. Add instrumentation and capture a current-path baseline first.
3. Implement read models, retrieval/delta improvements, and execution modes behind independent flags whose off state is the current 20.x path.
4. Integrate FAST after component contract tests pass; keep Limited ReAct separately disabled.
5. Compare the same workload and publish sample size plus P50/P95/P99 before setting rollout gates.
6. Roll back by disabling the affected flag; do not migrate authoritative data into caches or projections.
