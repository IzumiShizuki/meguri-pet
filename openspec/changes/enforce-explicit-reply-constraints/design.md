## Context
The four observed failures are intact model output rather than stream corruption. Current context already separates raw user input and untrusted clarification; repeat dynamics can encourage extra acknowledgements. Existing persona remains authoritative.

## Goals / Non-Goals
Honor recognized exact literals, explicit Han ranges/limits and one-sentence requests. Preserve unquoted current-user scope and normal persona. No general natural-language parser, factual verifier, arbitrary JSON formatter, Chinese word counter, truncation, padding, or model changes.

## Decisions
Use a conservative bounded parser on raw current input only. Mask quoted/code/blockquote spans before recognizing direct clause starts. Exact reply payloads remain user data, never system instructions. Reject conflicting/ambiguous matches rather than guess. Han means Unicode Han code points, excluding punctuation and emoji; sentence validation handles Chinese punctuation, closing quotes, emoji and decimal dots. Suppress repeated-message acknowledgement only when a current constraint exists.

Use the existing structured response route for constrained requests, including normally streamed turns. One complete validated delta preserves stream/snapshot identity. At most one regeneration with the same complete context and a data-only validation summary, no invalid-answer injection. Refit prompt budget; reuse concurrency guard and original typed deadline; no new retry deadline. Persistent violation fails with a content-free provider error. Unconstrained routes remain unchanged.

The coordinator queries streaming support per request so held replies use the existing non-native complete-answer path and do not claim provider token timing. Legacy callers have no typed deadline; they retain interruption handling and the one-repair cap. The original turn runtime remains responsible for cancellation/deadline delivery.

Review revisions: a visible withdrawal/changed requirement causes conservative non-enforcement; quoted withdrawal does not. Code fences retain delimiter type and opening length. Chinese single quotation marks are masked. Quoted exact literals stop at their closing delimiter; only an empty suffix, punctuation or a compatible no-period suffix is recognized, otherwise enforcement is declined. Unquoted instruction-like tails are ambiguous. Sentence counting protects code, URLs, qualified names, common abbreviations, versions and decimals without hiding punctuation between real sentences.

Provider telemetry records exactly one success/failure for each attempted model call. Local constraint validation, repair budget rejection and encoding failures do not create an extra model call. Local failures use REPLY_CONSTRAINT_VIOLATION or REPLY_CONSTRAINT_VALIDATION_FAILED; expiry uses TURN_DEADLINE_EXCEEDED, not stream interruption. Re-check cancellation and the original typed deadline before starting a repair and after receiving a response. Preserve the resolved persona rather than naming a fixed character.

The repair bound is per answer candidate: primary answer at most two calls, and an explicitly sampled alternative at most two more, so an A/B comparison can use four answer-model calls. Input/planner/other model routes are outside this count. No claim of two (or four) calls for every entire turn is made. Gate-controlled tests cover cancel/timeout during the second call, late completion, one terminal event, no rejected text, slot release and the next turn's recovery.

## Risks / Trade-offs
Constrained turns wait for a full answer and may incur one extra provider request. Live-model success and latency remain unverified offline. Unsupported/ambiguous wording passes to the normal model without deterministic enforcement. Bilingual mode conflicts are left to existing handling. This is scoped syntax, not universal prompt-injection resistance.

This change applies to model-generated replies. Deterministic skill/weather responses and later specialized presentation augmentation are outside this validator; their output constraints need separate acceptance. Sentence counting recognizes common Chinese/ASCII sentence terminators and decimal points, not all abbreviations or linguistic sentence structure. A persistent mismatch becomes a failed turn, not a silently altered answer.

## Migration Plan
Review isolated commit and bundle, cherry-pick through main owner, run targeted/full Java checks before separate deployment. No runtime migration.
