## 1. Structured summary model and compatibility

- [x] 1.1 Add versioned structured summary, fact, category, status, and refactoring-operation value types while preserving legacy `DerivedSummary` JSON fields.
- [x] 1.2 Validate structured facts against source message IDs, active-branch membership, status, supersession links, and source revision digest.
- [x] 1.3 Add JSON round-trip tests for legacy summaries, structured summaries, missing fields, stale summaries, and persisted GraphSnapshot compatibility.

## 2. Refactoring strategy and worker

- [x] 2.1 Add `ContextRefactoringStrategy` and deterministic conservative implementation with bounded output and no recursive summary input.
- [x] 2.2 Add optional semantic strategy seam with schema/source validation and deterministic fallback behavior.
- [x] 2.3 Extend precompression job identity and persistence with strategy/profile revision while preserving claim, lease, retry, and recovery semantics.
- [x] 2.4 Update the precompression worker to materialize independent structured summaries and add idempotency, stale-branch, fallback, and failure tests.
- [x] 2.5 Add feature flags and configuration for structured compression, semantic compression, strategy revision, and worker limits with new behavior disabled by default.

## 3. Context request, topic signal, and selective recovery

- [x] 3.1 Add `TopicSignal` and `TopicDetector` with deterministic topic detection independent of retrieval query rewriting.
- [x] 3.2 Extend `ContextBuildRequest` with current input, topic signal, and bounded `RehydrationPolicy`, retaining compatibility constructors for existing callers.
- [x] 3.3 Implement deterministic fact ranking and active-path source-span selection with the default three-fact, three-span, 1024-token, two-message-window limits.
- [x] 3.4 Extend `ContextBundle` with rehydration decisions and make automatic rehydrated blocks optional while preserving required current input and explicit references.
- [x] 3.5 Integrate THINK-only automatic rehydration into `CompanionContextRuntime` and keep FAST/AGENT automatic recovery disabled.
- [x] 3.6 Add tests for topic/query separation, mode behavior, relevance ranking, deduplication, sibling isolation, explicit references, and budget truncation.

## 4. Read model and provider projection integration

- [x] 4.1 Add the verified conversation read-model provider seam and use the full Harness fallback when projection data is absent, stale, malformed, or digest-incompatible.
- [x] 4.2 Rebuild or invalidate the derived projection after branch, edit, resume, reference, and stale-summary changes without changing Message DAG authority.
- [x] 4.3 Project structured summaries into compact model-facing content while keeping internal source/fact/trace identifiers server-side.
- [x] 4.4 Update provider serialization and canonical prompt digest behavior so context is represented once and volatile provenance does not affect semantic digests.
- [x] 4.5 Add provider privacy/trust, read-model fallback, exact replay, and no-duplicate-context tests.

## 5. Evaluation and rollout verification

- [x] 5.1 Add replay fixtures for superseded constraints, decisions, exact recall, causal/temporal questions, interleaved topics, branches, quotes, and stale summaries.
- [x] 5.2 Add measurements for compression ratio, state/constraint retention, exact recall, rehydration hit/unnecessary rates, prompt tokens, TTFT, cost, and fallback failures.
- [x] 5.3 Run Java context/provider/runtime tests plus OpenSpec validation with all new flags off and each new capability enabled independently.
- [x] 5.4 Document rollout, rollback, Python compatibility boundaries, and `NOT_MEASURED` handling when matched TTFT baselines are unavailable.
