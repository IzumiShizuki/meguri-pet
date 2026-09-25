## Context

See `proposal.md` for motivation and observable behavior. The current Java path stores an immutable `SessionContextStore.GraphSnapshot`, a `DerivedSummary` with plain extractive content, explicit-reference rehydration, a typed `ContextBundle`, and a tokenizer-backed `TokenBudgetAllocator`. `CanonicalTurnPipeline` currently passes `RetrievalPlan.query().rewrittenQuery()` as the topic hint. The existing TTFT/read-model change is still the upstream contract; this change consumes its rebuildable conversation projection when present and preserves the full Harness fallback.

## Goals / Non-Goals

**Goals:**

- Add a backward-compatible structured summary representation with fact-level source provenance and supersession.
- Make precompression deterministic by default, pluggable for an optional semantic backend, idempotent, and branch-safe.
- Add bounded THINK-only automatic rehydration while preserving explicit reference behavior.
- Separate topic detection from retrieval rewriting and keep topic changes reversible.
- Keep server-side provenance/traces separate from model-facing compact content.

**Non-Goals:**

- Migrating Python's deque path to Java DAG semantics.
- Adding normalized authoritative tables for compressed facts.
- Enabling semantic compression, automatic rehydration, or read-model shortcuts by default.
- Adding learned relevance models, LLMLingua, full-history prompting, or formal-memory writes.

## Decisions

### 1. Extend the existing summary JSON instead of adding fact tables

`SessionContextStore.DerivedSummary` remains the durable aggregate embedded in `GraphSnapshot`. It keeps `content`, `sourceMessageIds`, `modelRevision`, `contextRevision`, `sourceRevisionDigest`, and `status`, and adds a nullable structured payload. The payload contains `schemaVersion`, `gist`, category lists (`currentState`, `decisions`, `constraints`, `openThreads`, `exactItems`), a normalized `facts` list, and refactoring operations.

Each fact contains `factId`, `text`, `sourceIds`, `importance`, `status`, and `supersedesFactIds`. `status` is `ACTIVE` or `SUPERSEDED`; operation values are `KEEP_EXACT`, `COMPRESS`, `DROP`, `MERGE`, and `SUPERSEDE`. The existing compact `content` remains the legacy/provider projection fallback. Missing structured fields are treated as legacy and cannot trigger automatic rehydration.

Alternative: normalized fact tables. Rejected for V1 because GraphSnapshot revision saves already provide atomic source/snapshot persistence, and separate tables would introduce a second authority and migration path.

### 2. Use a strategy seam with deterministic default

Add a `ContextRefactoringStrategy` interface that receives a frozen active source range and returns a validated structured result. `DeterministicContextRefactoringStrategy` is the default and performs conservative extraction: exact-looking user constraints become exact items, repeated/acknowledgement content may be dropped or merged, and uncertain content remains retained rather than invented. An optional semantic implementation can be injected into the worker but never into the synchronous Turn path.

Semantic results pass schema, source membership, active-branch, size, and status validation. Any failure falls back to the deterministic result. The worker idempotency key becomes `conversationId:graphRevision:modelId:strategyRevision`; completed summaries are never recursively summarized or overwritten.

Alternative: always invoke an LLM. Rejected because it would add latency/cost and make offline replay depend on an external provider.

### 3. Add explicit context policy and rehydration records

Extend `ContextBuildRequest` with `currentInput`, `TopicSignal`, and `RehydrationPolicy`, retaining a compatibility constructor for existing callers. `RehydrationPolicy` carries enabled state, maximum facts/spans/tokens, before/after window limits, and a policy revision. The canonical pipeline enables it only when the authoritative execution mode is THINK; direct context callers can select it explicitly for tests.

`ContextBundle` adds immutable `RehydrationDecision` records. The allocator treats automatically selected `REHYDRATED` blocks as optional. Existing explicit exact-reference blocks remain required and retain their current six-before/four-after window.

The selector scores normalized lexical overlap, exact-term overlap, fact importance, and uncertainty cues. A zero-match fact is rejected. It selects at most three facts and three deduplicated source spans, with a default 1024-token automatic recovery budget and two messages on each side of a source. Source membership and current digest are checked again before materialization.

Alternative: automatically rehydrate in all modes. Rejected because it would change FAST/AGENT latency and token behavior.

### 4. Separate topic signal from retrieval query

Add a `TopicDetector` interface and deterministic implementation returning `TopicSignal(label, confidence, boundaryReason, detectorRevision)`. `CanonicalTurnPipeline` obtains the signal from the raw user input and current context, while the rewritten query remains owned by retrieval. `TopicSegmentResolver` continues to create candidates and only accepted signals change the active segment.

Alternative: keep using the rewritten query. Rejected because retrieval optimization can change wording without a topic boundary and would make summary/read-model selection unstable.

### 5. Keep provenance server-side and project compact content to providers

`ContextBundle.Block.sourceIds` and trace records remain the server-side audit fields. `PromptPolicyComposer` creates a compact content projection for structured summaries, and provider serialization omits internal fact/source/trace IDs from model-visible context blocks. Trust tags and user-facing citations remain intact. `ProviderRequest.canonicalPromptDigest()` uses semantic role/source/trust/revision/content and excludes volatile trace identifiers.

Alternative: expose all provenance to the model. Rejected because identifiers consume context budget and are not useful facts for the model.

### 6. Read-model usage is verified and fail-closed

The conversation read model is a rebuildable projection keyed by graph revision, active leaf, source digest, and summary strategy revision. Ordinary turns may use it for recent messages, structured summaries, references, token counts, and fact indexes. QUOTE, RESUME_FROM, TOPIC_LINK, edits, branch switches, stale summaries, missing projections, or mismatched digests use the full Harness and rebuild the projection afterward. A read-model error never upgrades authority or formal memory.

Because the current workspace does not yet contain the upstream read-model implementation, the integration is isolated behind a small `ConversationContextReadModelProvider` seam. The provider defaults to a miss/full-Harness path until the upstream change supplies the verified projection; no duplicate authority is introduced.

## Risks / Trade-offs

- [Deterministic extraction misses nuanced natural-language supersession] → Keep uncertain content as exact/legacy content, expose the semantic strategy as opt-in, and evaluate state/constraint retention before enabling it.
- [Automatic recovery adds TTFT or token cost] → Restrict it to THINK, use hard independent limits, feature-flag it, and record unnecessary/hit rates.
- [Structured summary becomes stale] → Reuse source revision digest and active-path validation for every read and write.
- [Provider projection accidentally drops trust] → Test every trust type at both ContextBundle and wire payload boundaries.
- [Read-model API differs from the upstream change] → Keep the provider seam local, use full-Harness fallback, and avoid making the projection required for correctness.

## Migration Plan

1. Add JSON-compatible fields and flags with all new behavior disabled.
2. Deploy deterministic precompression in shadow mode and compare legacy versus structured projections without changing provider input.
3. Enable verified read-model hits only after the upstream projection contract and fallback tests pass.
4. Enable THINK automatic rehydration for a bounded allowlist and compare token, quality, TTFT, and fallback metrics.
5. Optionally enable the semantic worker backend; invalid or unavailable results immediately fall back to deterministic output.
6. Roll back by disabling flags. Existing raw DAG data and legacy summaries remain readable; no destructive migration is required.
