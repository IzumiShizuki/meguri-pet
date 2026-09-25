## Why

Meguri's current 20.x runtime has durable Turn, retrieval, persona, capability, and adapter foundations, but it cannot yet prove where time-to-first-token (TTFT) is spent or select a thin ordinary-chat path without conflating retrieval depth, reasoning depth, and tool execution. The Notion follow-up plan therefore needs a contract-first, measurable change whose planned work is not mistaken for already shipped behavior.

## What Changes

- Introduce a versioned performance contract for TTFT traces, `FAST | THINK | AGENT` execution decisions, persona/context read models, retrieval quorum decisions, and recoverable delta chunks.
- Add an opt-in FAST path that omits unnecessary pre-provider work while preserving authoritative state, absolute deadlines, policy checks, event persistence, replay, cancellation, and idempotency.
- Make the first semantic `text.delta` bypass later-token coalescing, then allow configurable micro-batching for subsequent deltas.
- Add a bounded Limited ReAct runtime that is eligible only in AGENT mode and uses the existing Capability and AgentTask authority boundaries.
- Isolate the optional Agent-planning control-plane call from ordinary generation with a bounded non-thinking provider route, independent concurrency, physical request deadline, and sanitized failure classification.
- Permit the optional Agent-planning route to use a separate secret file while explicitly falling back to the ordinary LLM secret when no planner secret is configured.
- Require before/after TTFT evidence with the same release manifest, workload, client metric, and percentile definitions; no latency target or improvement value is asserted before a real baseline exists.

## Capabilities

### New Capabilities

- `turn-ttft-observability`: Versioned, privacy-safe end-to-end TTFT timestamps, derived durations, and comparable percentile reports.
- `turn-execution-mode`: Server-authoritative FAST, THINK, and AGENT decisions that remain independent from retrieval depth and are bounded by one absolute deadline.
- `persona-context-read-model`: Rebuildable persona and conversation projections with explicit version invalidation and deterministic full-harness fallback.
- `retrieval-fast-path`: Layered retrieval gating, speculative lanes, conditional reranking, and minimum-evidence early completion without weakening ACL or trust.
- `streaming-delta-delivery`: Immediate durable first-delta delivery followed by configurable, recoverable coalescing and client-visible timing.
- `limited-react-runtime`: A finite, policy-checked AGENT-only action/observation loop with deterministic termination and untrusted observations.

### Modified Capabilities

<!-- No archived OpenSpec capabilities exist yet. Existing 20.x behavior remains the compatibility baseline. -->

## Impact

- Affects the Java Turn/Persona/Context/Retrieval/Provider/Capability runtimes, the Python retrieval gateway, and Website/AIRI/AstrBot timing adapters when implementation begins.
- Adds shared JSON Schema and fixtures under `contracts/performance/v1`; Java/Python/TypeScript implementations must consume the same revision rather than create parallel DTO meanings.
- Adds optional adapter fields and events only behind feature flags. Missing execution-mode input must retain the current stable path until a separately measured rollout changes the default.
- Does not authorize changing PostgreSQL authority, broadcasting before persistence, relaxing policy, or reporting an unmeasured TTFT improvement.
