# Performance Contract v1 and code compatibility map

Status: **PROPOSED / PLANNED**. This document maps the Notion follow-up design
to the repository as inspected on branch `codex/ttft-fast-path-limited-react` on
2026-08-01. It is not an implementation-complete statement.

## Sources

- [Performance and execution optimization plan](https://app.notion.com/p/3ada3636596381b4a64dfd77fb52dff9)
- [08 multi-agent integration and contract freeze](https://app.notion.com/p/3aea36365963817da5e2d90830337bd0)
- [0 current implementation architecture](https://app.notion.com/p/3aaa3636596381a3ab30ebe83c75e3e9)
- Relevant detailed pages 01, 02, 03, 04, and 06 were used only to disambiguate
  fields named by the integration page.

## Existence conclusion

Before this change, no `openspec` or `open-spec` path existed in tracked,
untracked, hidden, or ignored repository files. The machine already provided
the official `@fission-ai/openspec` CLI version 1.7.0. The new local structure
therefore follows its `spec-driven` schema:

```text
openspec/config.yaml
openspec/specs/
openspec/changes/archive/
openspec/changes/optimize-ttft-fast-path-limited-react/
  .openspec.yaml
  proposal.md
  design.md
  tasks.md
  specs/<capability>/spec.md
```

All task checkboxes remain open. Artifact completeness means the proposal is
ready for implementation; it does not mean the behavior has shipped.

## Notion-to-code match

| Planned contract/behavior | Current repository evidence | Match | Required integration action |
| --- | --- | --- | --- |
| `TurnExecutionMode` and explainable decision | `com.meguri.core.execution` now owns FAST/THINK/AGENT, deterministic signals, server-clipped budgets, decision reasons, and ReAct eligibility. The optional adapter input is forwarded across Java/Python/TypeScript. | Implemented behind flags | Persist/rehydrate the complete decision as first-class Turn metadata in addition to the durable event/trace copy. |
| `RetrievalMode = NONE/FAST/SLOW` | `harness/retrieval/RetrievalMode.java` is the Turn wire type and defaults missing input to SLOW; `retrieval/RetrievalMode.java` duplicates the enum internally. Adapter protocol v1 already exposes the values. | Present with duplication risk | Keep the wire values and adapt/consolidate the internal duplicate; do not add a third enum. |
| `EffectivePersonaState` | `persona/runtime/EffectivePersonaState.java` and the reducer/facade already compose persona, relationship, scene, interaction, temporal mode, policy, and client capability. | Strong foundation | Extend composition to reference a versioned base snapshot plus turn-dynamic inputs. |
| `PersonaBaseSnapshot` | `PersonaRuntimeFacade` keeps a process-local map of complete `EffectivePersonaState` for safe fallback, keyed only by persona/user/conversation. It contains dynamic state and has no authority-version invalidation. | Semantically different | Add the stable version-keyed projection; retain safe fallback without treating it as the planned cache. |
| `ConversationContextReadModel` | `SessionContextStore.GraphSnapshot` and `ContextBundle` contain active leaf, revisions, messages, references, summaries, topic, and tokenized blocks. `CompanionContextRuntime.build` still resolves the graph/branch/topic/summary/reference path for a normal build. | Partial | Add the one-read projection and deterministic full-harness fallback conditions. |
| `RetrievalDecision` and `EvidenceQuorum` | `RetrievalGate` has a cheap NONE rule and plan validation. `DefaultRetrievalPlanner` enables Lore/Memory/Knowledge (plus Web for SLOW). `RetrievalRuntime` schedules lanes concurrently but joins every future before assembly. | Partial | Add layered decisions, speculative provenance, conditional rerank policy, and quorum-aware early completion/cancellation. |
| `RetrievalBundle` | Both `harness/retrieval/RetrievalBundle.java` and the richer `retrieval/RetrievalBundle.java` exist; the canonical pipeline uses the richer type. Items already carry citation, trust, token count, version time, graph evidence, degradation, and trace. | Strong foundation with duplicate type | Use the richer domain bundle as owner and add decision/quorum/minimum-ready/all-settled semantics through one adapter. |
| `CapabilitySnapshotVersion` | `CapabilityRegistry.CapabilitySnapshot` already has `snapshotId`, `frozenAt`, and grants; `ExposurePlan` carries the snapshot ID. | Present | Bind the v1 wire value to `snapshotId` and add precompiled schema lifecycle separately. |
| `TurnEventEnvelope` | Adapter-protocol v1 and `dto/EventEnvelope.java` already define persistent IDs, session sequence, replay policy, metadata, data, and version. | Present | Reuse it unchanged; delta metadata is additive. |
| Native Provider stream | `LangChain4jLlmProvider.stream` forwards `onPartialResponse` values through a Flux and does not collect the response before streaming. | Present with audit risk | Measure semaphore/connection wait and verify real Provider first-byte/first-token behavior. |
| Immediate first delta plus later coalescing | `NativeTextDeltaAggregator` emits the first non-empty Provider token immediately, then applies the existing coalescing policy. `TurnOrchestrator.emit` marks first-delta persistence only after journal append returns. | Implemented and synthetically measured | Make later-token thresholds externally configurable and retain byte-for-byte replay verification. |
| Durable/replayable SSE | `TurnJournal.appendExecution`, the PostgreSQL/in-memory journals, `RuntimeWebController`, cursor replay, no-cache, and `X-Accel-Buffering: no` provide the required baseline. | Strong foundation | Add commit/flush/client receive/render timestamps and proxy E2E evidence without changing authority order. |
| `TurnLatencyTrace` | `com.meguri.core.observability` now supplies the performance.v1 trace, monotonic recorder, missing reasons, dimensioned P50/P95/P99 statistics, privacy tests, and terminal-event trace output. Server milestones are integrated; socket/client marks remain explicitly missing. | Implemented server-side; transport/client partial | Add an HTTP/SSE flush acknowledgement and client receive/render ingestion before claiming production E2E TTFT. |
| Limited ReAct | `com.meguri.core.react` now provides the five-decision, AGENT-only, read-only, at-most-three-round runtime, deterministic termination, normalized untrusted observations, and a frozen-Capability executor. The Turn path has an optional planner seam behind `MEGURI_LIMITED_REACT_ENABLED`. | Implemented as guarded MVP | Add durable PostgreSQL trace/recovery, shared dispatcher lifecycle, minimum candidate exposure, and DELEGATE_AGENT/approval adapters before wider rollout. |

## Frozen execution fields

Java owner package: `com.meguri.core.execution`.

```text
CurrentTurnSignals
  userRequestedMode                 -> user_requested_mode (nullable)
  skillRequiredMode                 -> skill_required_mode (nullable)
  classifierCandidateMode           -> classifier_candidate_mode (nullable)
  intentTags                        -> intent_tags
  externalObservationRequired       -> external_observation_required
  multiStepExecutionRequired        -> multi_step_execution_required
  toolCapableClient                 -> tool_capable_client
  remoteAgentCapableClient          -> remote_agent_capable_client
  remainingDeadlineMillis           -> remaining_deadline_millis

ExecutionModeDecision
  mode
  reasonCodes                       -> reason_codes
  budget
  userExplicit                      -> user_explicit
  reactEligible                     -> react_eligible

ExecutionBudget
  maxPreProviderStages              -> max_pre_provider_stages
  maxModelCalls                     -> max_model_calls
  maxRetrievalCalls                 -> max_retrieval_calls
  maxToolCalls                      -> max_tool_calls
  maxRemoteAgents                   -> max_remote_agents
  maxAgentDepth                     -> max_agent_depth
  maxRounds                         -> max_rounds
  repeatedActionLimit               -> repeated_action_limit
  maxTokens                         -> max_tokens
  maxCostUnits                      -> max_cost_units
  deadlineAt                        -> deadline_at (absolute Instant)
```

`CurrentTurnSignals` is server-assembled. `ExecutionModeDecision` has exactly
the five semantic fields above; revision and timestamps belong in trace/event
metadata, not the Java decision record.

## TTFT boundaries

The exact timestamp spellings are defined in the schema README. The two client
boundaries are deliberately separate:

```text
first_delta.sse_flushed_at
-> client.first_delta_received_at   = sse_transport_latency
-> client.first_render.at           = client_render_latency
```

`end_to_end_ttft` is measured from `turn.received_at` to
`client.first_render.at`. When the client cannot report a boundary, the trace
omits it and supplies a reason code; it must not substitute a different server
timestamp under the same name.

## Downstream rules

- Turn owner consumes persona/context/retrieval/provider outputs; it does not
  reimplement their algorithms.
- Persona/context owner treats all projections as rebuildable and does not
  cache current input, retrieval, tool output, exemplar choice, or temporal
  calculation in the base snapshot.
- Retrieval owner keeps current-input topic changes visible and never lets
  early completion widen trust or ACL.
- Streaming owner reuses the existing event envelope and proves persisted text,
  sequence, replayed text, and terminal state are identical after coalescing.
- Observability owner reports matched sample size and P50/P95/P99. Until both
  comparable production measurements exist, the answer to “TTFT improved from what to what?” is
  `NOT_MEASURED`.

## Feature flags and rollback

The planned flags are independent and off-to-current-path reversible:

```text
MEGURI_EXECUTION_MODE_ENABLED
MEGURI_FAST_PATH_ENABLED
MEGURI_PERSONA_READ_MODEL_ENABLED
MEGURI_RETRIEVAL_SPECULATION_ENABLED
MEGURI_CONDITIONAL_RERANK_ENABLED
MEGURI_DELTA_COALESCING_ENABLED
MEGURI_LIMITED_REACT_ENABLED
```

Disabling a flag must select the current stable implementation, not a mock or
synthetic success. Defaults remain an implementation/configuration task and are
not asserted by this proposed contract.
