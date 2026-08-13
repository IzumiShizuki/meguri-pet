## Why

Meguri's Java context runtime already preserves an immutable Message DAG, branch-safe summaries, token budgets, and replay traces, but its precompression is still a single extractive string and rehydration is only triggered by explicit references. This change makes compressed context structured and provenance-aware, then adds a bounded THINK-only recovery path without weakening the DAG, trust, memory, or replay boundaries.

## What Changes

- Evolve `DerivedSummary` with a backward-compatible structured compressed-context schema: facts, current state, decisions, constraints, open threads, exact items, supersession links, and fact-level source IDs.
- Add a pluggable asynchronous `ContextRefactoringStrategy` with a deterministic offline default and an optional semantic backend that falls back safely on timeout or invalid output.
- Add bounded, deterministic selective rehydration for THINK turns while preserving the existing QUOTE/TOPIC_LINK behavior for every mode.
- Separate topic detection signals from retrieval query rewriting and retain reversible topic candidate semantics.
- Extend context budgets and traces with rehydration decisions, token usage, truncations, and safe prompt projections that keep internal provenance out of model-visible content.
- Reuse the existing `ConversationContextReadModel` work as a rebuildable projection; complex history operations fall back to the authoritative full Harness.
- Keep Python's bounded-deque/session-summary path as a compatibility adapter and keep all new capabilities disabled by default.

## Capabilities

### New Capabilities

- `structured-context-compression`: Versioned structured summaries, fact-level provenance, deterministic/optional semantic refactoring, worker recovery, and GraphSnapshot JSON compatibility.
- `selective-context-rehydration`: THINK-only query-aware source recovery with independent limits, traceability, and token-budget enforcement.
- `topic-signal-separation`: Independent topic detection inputs and reversible topic-boundary handling separate from retrieval query rewriting.
- `context-prompt-projection`: Compact model-facing projections that preserve trust and semantic digests while withholding internal provenance identifiers.

### Modified Capabilities

<!-- No repository-level main specs exist yet; all requirements introduced here are new deltas. -->

## Impact

- Java `SessionContextStore`, context models, `CompanionContextRuntime`, precompression lifecycle, and context persistence JSON.
- Java turn preparation, topic resolution, prompt composition, provider serialization, and context read-model integration.
- New configuration flags and conservative rehydration limits; no new authoritative database tables or Python semantic migration.
- Adds unit, integration, replay, compatibility, privacy, and evaluation fixtures while preserving the unrelated dirty workspace file.
