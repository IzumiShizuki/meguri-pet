## Why

The isolated dialogue evaluation exposed missed explicit searches, irrelevant search results, weather documentation being treated as local forecasts, and unreliable turn completion. The fix must preserve the user's saved baseline and distinguish confirmed application defects from model errors and evaluation-client limitations.

## What Changes

- Respect explicit web requests and offline constraints, extract bounded subject queries, validate search relevance and allow one bounded recovery search.
- Separate technical weather questions from forecasts and prevent unscoped conversations from using the configured owner's location.
- Bind prompt history, current input, event replay and recovery to the current turn; make correction and derived-fact precedence explicit.
- Reduce repeated unsolicited care and clarify candidate sampling versus formal memory consent.
- Inspect the empty skill catalogue without installing or enabling anything.

## Capabilities

### New Capabilities

- `dialogue-evaluation-reliability`: Cross-cutting routing, turn isolation, factual precedence and bounded recovery requirements.

### Modified Capabilities

None. Existing unarchived changes remain untouched.

## Impact

Java Core search, weather, context, orchestration and training policy; corresponding unit/integration tests and, if required, desktop recovery. No production deployment, new credentials, model-setting changes or formal memory writes. Public search and synthetic test data only.
