## Decision

Keep the web-search decision deterministic and server-side because Meguri's current retrieval runtime selects the Web provider before the LLM call. Add multiple intent families to `WebSearchPolicy` instead of introducing a second model call or a new tool protocol.

## Classification

- Explicit search: `联网搜索`, `查一下`, `look up`, and equivalents.
- Freshness: `今天`, `刚刚`, `最新`, `最近`, `current`, `latest`, `recent`, `today`, `now`.
- Volatile facts: news, announcement, release, update, status, price, schedule, score, weather, and equivalent terms.
- Entity update shape: a named subject followed by a question about posting, releasing, changing, supporting, or current availability.

Require a currentness or volatile-fact signal for automatic selection. This prevents broad entity mentions from causing network traffic. Explicit search remains sufficient on its own.

## Safety

The existing `MEGURI_WEB_SEARCH_ENABLED` flag remains the final network gate. The classifier only changes whether the already-registered provider is eligible. It does not bypass HTTPS, allowlists, timeouts, result limits, or citation handling.

## Verification

Extend `WebSearchGatewayTest` with positive current-information cases and negative conversational/creative cases. Run the focused Maven test, then the Core test suite if the focused test passes.
