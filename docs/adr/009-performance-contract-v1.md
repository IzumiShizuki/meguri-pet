# ADR 009: Performance and execution shared contract v1

Status: proposed.

Date: 2026-08-01.

## Context

The Notion follow-up plan splits TTFT observability, execution modes, persona
and context read models, retrieval fast paths, streaming deltas, and Limited
ReAct across parallel owners. The current repository has compatible foundations
but no OpenSpec root and no single shared performance contract. It also already
contains two Java `RetrievalMode` enums and two `RetrievalBundle` types, making
uncoordinated same-name additions particularly risky.

The follow-up plan is explicitly `FOLLOW-UP / PLANNED`. Existing code, tests,
and production evidence remain the implementation truth until the OpenSpec
tasks are verified.

## Decision

Adopt `contracts/performance/v1/performance-contract.schema.json` as the
canonical cross-language wire definition for the shared names listed in Notion
08. Use `contract_revision: performance.v1` on v1 objects that cross a module or
process boundary.

The following rules are part of the decision:

1. `TurnExecutionMode = FAST | THINK | AGENT` remains orthogonal to existing
   `RetrievalMode = NONE | FAST | SLOW`.
2. Only the server constructs `CurrentTurnSignals`, clips `ExecutionBudget`,
   and decides `react_eligible`.
3. Budgets carry the Turn's absolute deadline; children receive subsets.
4. Persona/context caches are versioned rebuildable projections, never
   authority sources.
5. Retrieval quorum may shorten waiting but cannot weaken ACL, ACTIVE version,
   trust, citation, or evidence requirements.
6. The first non-empty Provider token bypasses later-token coalescing, but its
   event is broadcast only after durable append.
7. `TurnEventEnvelope` reuses adapter-protocol v1 rather than creating another
   event shape.
8. TTFT claims require matched sample sets and P50/P95/P99. Without comparable
   before/after evidence, the result is `NOT_MEASURED`.
9. Limited ReAct is AGENT-only, independently flagged, finite, and constrained
   by existing Capability/AgentTask policy, approval, idempotency, and trust
   boundaries.

## Alternatives considered

### Let each feature branch define local DTOs

Rejected. This makes integration depend on late semantic reconciliation and
would compound existing duplicate retrieval types.

### Put all shared Java records in a new contract package

Rejected for this revision. The repository already has domain-owned public
types; a second Java mirror would create conversion and ownership ambiguity.
The JSON Schema freezes the cross-language shape while each domain owns its
binding.

### Optimize first and instrument later

Rejected. It would make TTFT attribution and improvement claims unverifiable,
especially across Provider, SSE, proxy, and client boundaries.

## Consequences

- Parallel agents can implement against stable wire names and fixtures.
- Existing duplicate retrieval types need an explicit adapter/consolidation
  decision during implementation; no third representation is permitted.
- Additive optional v1 fields remain possible, but breaking meaning or required
  fields require a v2 directory and revision.
- No performance improvement can be reported from this ADR or its example
  fixture values; real measurements are a release gate.
