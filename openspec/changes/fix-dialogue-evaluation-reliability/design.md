## Context

See proposal.md for motivation. The reviewed baseline is 47462684e7f121ab9b429bb0feab9d54e582438f. The evaluation used isolated identities and the Core turn API; UI delivery, compression limits and actual imported skills were not established.

## Goals / Non-Goals

**Goals:** Fix reproducible defects at existing policy, context and event boundaries; retain bounded work and identity isolation.

**Non-Goals:** No broad state-store rewrite, special-case evaluation answers, new geocoding dependency, model change, skill installation or deployment.

## Decisions

1. Extend deterministic routing and strip conversational wrappers before strict URI query encoding. Check relevance against the normalized subject and allow at most one alternate search under the original deadline. Preserve source labels and safe URL filtering. Explicit public HTTPS links use the same safe extractor directly. Bound decoding to 2 MB, model content to 12,000 characters, and the sole-source page budget to 3 seconds (multiple results retain 1 second per page).
2. Weather documentation and code are not forecast intents. Only explicit reference to the configured place permits use of global forecast data; an unscoped request asks for a place instead of leaking the owner's location. Do not infer identity from a caller-supplied client label.
3. Inspect the actual prompt and graph lifecycle before changing context selection. Preserve chronological user corrections and explicitly distinguish user source facts from assistant-derived calculations. Any narrower change must be justified by regressions.
4. Drain persisted events after observing inactivity before ending SSE. Verify current turn binding, failure recovery and idempotency with a deterministic provider.
5. Training mode false prevents unsolicited alternative generation and archiving. Formal memory consent remains independent. Use conversation-local prompt guidance for repeated care and current preferences.
6. Record skill-directory evidence and avoid expanding permissions or enabling unreviewed skills.

## Risks / Trade-offs

- Lexical relevance can reject paraphrases: use conservative anchors and preserve diagnostics; no invented source claims.
- A configured global weather place is not an identity binding: require explicit place selection until a scoped preference store exists.
- LLM factual correctness is probabilistic: prompt precedence is not a general arithmetic engine; report residual failures honestly.
- Public search/provider availability can vary: deterministic tests establish application behavior; bounded live probes remain separate evidence.

## Migration Plan

No data migration. Commit and push a separate fix branch after tests; deployment remains a separate action. Rollback is selecting the saved baseline without changing user data.
