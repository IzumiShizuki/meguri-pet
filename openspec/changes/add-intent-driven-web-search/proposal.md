## Why

Meguri already has a bounded web-search provider, but its gate only recognizes explicit search phrases. Questions about current releases, news, people, products, or today's changes therefore receive stale or unsupported answers unless the user manually asks for web search.

## What Changes

- Replace the explicit-keyword-only gate with a deterministic current-information classifier.
- Trigger web retrieval for explicit search requests, freshness/currentness questions, and volatile entity update/status questions.
- Preserve fail-closed configuration, bounded retrieval, and no-search behavior for greetings, creative work, stable knowledge, and user-provided content analysis.
- Add regression tests for the Tobi/Codex-style query and negative cases.
- Record the mature-harness research and decision boundary in project documentation.

## Capabilities

### New Capabilities

- `intent-driven-web-search`: Automatic, bounded web-search selection for current-information questions.

### Modified Capabilities

None.

## Impact

Java Core web-search policy and tests. No credential, provider, or frontend changes are required.
