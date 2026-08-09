# Meguri Performance Contract v1

Status: **PROPOSED / NOT IMPLEMENTED**.

This directory freezes the cross-language wire contract for the OpenSpec change
`optimize-ttft-fast-path-limited-react`. It does not claim that the Java,
Python, TypeScript, database, proxy, or client work is complete.

## Canonical files

- `performance-contract.schema.json` is the normative JSON Schema.
- `fixtures/canonical-fast-turn.json` exercises every frozen contract family.
- `fixtures/mode-decisions.json` covers FAST, THINK, and AGENT decisions.
- `fixtures/delta-flow.json` covers immediate-first-chunk and replay ordering.
- `fixtures/latency-trace.json` covers stage omission and client timing.

Fixture limit values are examples, **not production defaults or SLOs**. Runtime
defaults and rollout thresholds must be derived from measured baselines.

## Frozen names

The v1 shared names are:

```text
TurnExecutionMode
RetrievalMode
ExecutionBudget
ExecutionModeDecision
CurrentTurnSignals
PersonaBaseSnapshot
EffectivePersonaState
ConversationContextReadModel
RetrievalDecision
EvidenceQuorum
RetrievalBundle
CapabilitySnapshotVersion
DeltaPolicy
TextDeltaChunk
TurnEventEnvelope
TurnLatencyTrace
```

`TurnEventEnvelope` is an alias of the existing adapter-protocol v1
`EventEnvelope`; its schema is referenced rather than copied. The wire
`RetrievalMode` remains `NONE | FAST | SLOW`. Implementations must not create a
third enum or a second event protocol with the same meaning.

## Compatibility policy

- The path and `contract_revision: performance.v1` fix the major revision.
- Required field names, enum values, and meanings are frozen for v1.
- Additive optional fields are allowed; consumers ignore unknown optional
  fields. A new required field, removed/renamed field, changed enum meaning, or
  changed authority rule requires `performance.v2`.
- Snake-case JSON names are canonical. Language-specific casing is a binding
  detail only.
- Client input may request `execution_mode`; it may not submit trusted
  `CurrentTurnSignals`, an `ExecutionBudget`, or `react_eligible`.

## Java ownership guidance

The wire contract does not require a duplicate Java contract package. Domain
owners bind the shared names in these packages:

| Contract | Java owner/binding |
| --- | --- |
| `TurnExecutionMode`, `CurrentTurnSignals`, `ExecutionBudget`, `ExecutionModeDecision` | `com.meguri.core.execution` |
| `RetrievalMode` wire input | existing `com.meguri.core.harness.retrieval.RetrievalMode` |
| `EffectivePersonaState` | existing `com.meguri.core.persona.runtime.EffectivePersonaState` |
| Persona/context read models | `com.meguri.core.persona.readmodel` and `com.meguri.core.context.readmodel` |
| Retrieval decision/quorum/bundle | `com.meguri.core.retrieval` (adapt the legacy harness DTO; do not clone semantics) |
| `CapabilitySnapshotVersion` | existing `CapabilityRegistry.CapabilitySnapshot.snapshotId()` |
| Delta policy/chunk | the Provider/Turn streaming boundary selected by the Streaming owner |
| `TurnEventEnvelope` | existing `com.meguri.core.dto.EventEnvelope` |
| `TurnLatencyTrace` | `com.meguri.core.observability` |

## TTFT timestamp spelling

These exact wire keys are canonical. In particular, keep
`client.first_render.at` as written and include the separately observable
client receive point.

```text
turn.received_at
turn.persisted_at
persona.started_at
persona.ready_at
retrieval_gate.started_at
retrieval_gate.ready_at
query_rewrite.started_at
query_rewrite.ready_at
retrieval.started_at
retrieval.minimum_ready_at
retrieval.all_settled_at
context_build.started_at
context.ready_at
capability_exposure.started_at
capability_exposure.ready_at
provider.request_sent_at
provider.first_byte_at
provider.first_token_at
first_delta.persisted_at
first_delta.sse_flushed_at
client.first_delta_received_at
client.first_render.at
```

Missing optional stage timestamps require a stable reason code. Character TTFT
ends at `client.first_render.at`; AstrBot or another non-character-stream client
uses `first_platform_message_latency` and must not be mixed into that
distribution.

## Required validation

Every Java/Python/TypeScript binding must deserialize these fixtures and assert
its own re-serialization/semantic invariants. The OpenSpec planning artifacts
must also pass:

```text
openspec validate optimize-ttft-fast-path-limited-react --type change --strict --no-interactive
```
