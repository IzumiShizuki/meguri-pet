# Mature Harness Web Search Routing

Date: 2026-10-17

## Findings

Mature harnesses treat web search as a bounded tool, not as a keyword-only mode.

- OpenAI's agent web search is enabled by adding a `web_search` tool. The model can use it when the task needs external information; `live`, `cached`, and `disabled` modes separate network access from availability. Leaving the tool out keeps search off. Source: OpenAI developer documentation, Web search.
- Anthropic documents the decision boundary explicitly: search for recent events, current prices or statistics, changing facts about organizations, people, or products, and explicit search requests. Do not search for stable facts, creative work, analysis of supplied content, greetings, or ordinary conversation. The model decides when to call the tool, while administrators and request settings bound domains and call counts. Source: Anthropic web search tool documentation.
- Gemini CLI describes its search tool as model-selected grounding for current events, current library versions, bugs, security issues, and online documentation, with citations and a separate fetch path for a known URL.

## Design Implications for Meguri

Meguri does not yet expose web search as a model tool call. Its current architecture has a deterministic server-side retrieval lane, so the closest safe implementation is a deterministic classifier with the same decision boundary:

1. Explicit search requests always opt in.
2. Currentness signals opt in: today, latest, recent, current, announcement, release, price, status, schedule, score, weather, and equivalent Chinese or Japanese terms.
3. Volatile subject signals opt in when the query asks about a named person, organization, product, service, or project and asks what it posted, released, changed, or currently supports.
4. Stable knowledge, greetings, creative requests, and analysis of user-provided text remain offline.
5. The feature flag remains fail-closed. Search results stay bounded and citation-bearing.

This lets a query such as "今天 Tobi 有发什么新的 Codex 更新吗" trigger search without requiring the user to say "联网搜索", while preserving the existing network and retrieval safety boundaries.
