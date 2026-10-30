## Why
Night QA L03 added a politeness prefix, L04 returned two sentences, and P02/P04 returned 265/206 Han characters for a 220–260 request. SSE exactly matched the snapshot. Provider output lacks explicit per-turn constraint validation.

## What Changes
- Recognize a bounded subset of direct current-user reply constraints, excluding quoted/source instructions and prior context.
- Add a mandatory user-data contract and fixed policy guidance, validate generated replies, and allow one deadline-bounded repair attempt.
- Hold explicitly constrained replies until validated; preserve native streaming/persona for ordinary conversation. Never truncate or pad content.

## Capabilities
### New Capabilities
- `explicit-reply-constraints`: conservative current-turn reply validation.

## Impact
Java LLM provider and focused offline tests only. No retrieval, service, model, authority or configuration changes.
