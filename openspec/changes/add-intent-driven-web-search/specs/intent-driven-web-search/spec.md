## Purpose

Automatically select the existing bounded web retrieval lane when a conversation depends on current external information.

## Requirements

### Requirement: Current-information questions trigger web retrieval
The system SHALL select web retrieval when a message contains an explicit search request, a currentness/freshness request, or a request for a volatile entity's latest update, release, announcement, status, price, schedule, or news.

#### Scenario: Current product update
- **WHEN** the user asks "今天 Tobi 有发什么新的 Codex 更新吗"
- **THEN** `WebSearchPolicy.shouldSearch` returns true

#### Scenario: Explicit search
- **WHEN** the user asks "帮我查一下最新的 DeepSeek API"
- **THEN** `WebSearchPolicy.shouldSearch` returns true

### Requirement: Stable and conversational messages stay offline
The system SHALL NOT select web retrieval for greetings, ordinary conversation, creative writing, mathematics, stable general knowledge, or analysis of text already supplied by the user unless the message also contains a current-information or explicit-search signal.

#### Scenario: Greeting
- **WHEN** the user says "你好，陪我聊聊天"
- **THEN** `WebSearchPolicy.shouldSearch` returns false

#### Scenario: Creative request
- **WHEN** the user asks "写一首关于春天的诗"
- **THEN** `WebSearchPolicy.shouldSearch` returns false

### Requirement: Network access remains fail-closed
The classifier SHALL only select the existing web provider; actual network access SHALL remain disabled when `MEGURI_WEB_SEARCH_ENABLED` is false. Search results SHALL continue to use the existing bounded provider and citation pipeline.

### Requirement: Query normalization remains bounded
The normalized search query SHALL remain bounded to 256 characters and SHALL remove common request wrappers without removing the subject of the query.
