## Purpose

Keep desktop chat, current-information lookup, reminder review, Todo and attachments usable together, with visible execution progress and recoverable failures. Users must be able to distinguish an actual network attempt from a local answer and must not lose their draft to an unrelated capability.

## ADDED Requirements

### Requirement: Ordinary requests remain chat messages
The desktop SHALL intercept only an explicit reminder instruction or an explicit scheduled external action. A request about current information or an immediate lookup SHALL reach chat. Temporal words and polite request wrappers alone MUST NOT trigger reminder review.

#### Scenario: Screenshot query is sent
- **WHEN** a user sends “帮我查看一下今天Codex是否更新了” or “今天帮我联网搜索Codex更新”
- **THEN** it reaches chat and no reminder review is opened

#### Scenario: Genuine reminders remain supported
- **WHEN** the user sends “明天下午三点提醒我喝水”
- **THEN** the existing reminder flow resolves a future Shanghai time and preserves account checks and duplicate-write protection

#### Scenario: Review does not block unrelated chat
- **WHEN** a Todo write is uncertain and the user sends an unrelated query or an attachment
- **THEN** the query remains sendable without retrying the Todo write

### Requirement: Current-information requests retain web retrieval
The Core SHALL preserve policy-approved web retrieval when selecting a fast execution path. Disabled retrieval SHALL remain disabled. Failed and empty lookup outcomes SHALL remain distinguishable from successful results.

#### Scenario: Current update lookup
- **WHEN** web search is enabled and the user asks about today's Codex update without an execution mode override
- **THEN** web retrieval is attempted and its outcome is observable

#### Scenario: Lookup unavailable
- **WHEN** a provider returns unavailable or empty results
- **THEN** the turn exposes the corresponding diagnostic and does not claim verified current information

#### Scenario: Retrieved pages exceed context budget
- **WHEN** a successful lookup returns pages larger than the available retrieval context budget
- **THEN** the turn uses bounded excerpts with source attribution and continues or reports a controlled retrieval degradation instead of failing the entire send

#### Scenario: Conversational request becomes a useful query
- **WHEN** the user asks “帮我查看一下今天Codex是否更新了”
- **THEN** the search query prioritizes the subject and update intent rather than the conversational request wrapper, while retaining its freshness meaning

#### Scenario: System retrieval is distinguished from callable tools
- **WHEN** system web retrieval has actually completed but no web tool schema is exposed to the model
- **THEN** the response can truthfully describe the performed lookup and MUST NOT infer that networking is unavailable solely from an empty callable-tool list
- **AND** irrelevant or insufficient results MUST NOT be described as having verified today's update

#### Scenario: Explicit offline instruction takes precedence
- **WHEN** the user explicitly requests “不要联网搜索，只总结这段文字” or “不要联网，告诉我今天Codex是否更新”
- **THEN** the turn does not perform web lookup despite the presence of search or freshness words

#### Scenario: Explicit online query wording
- **WHEN** the user explicitly requests “联网查询Codex版本” or “联网查Codex版本”
- **THEN** those lookup instructions are eligible for web retrieval under the existing global network gate

### Requirement: Public execution progress is visible
The desktop SHALL display concise per-turn status for connecting, retrieving, tool execution, generating, completion, cancellation and failure when those signals exist. A collapsible view SHALL contain only public progress and outcome summaries, excluding internal reasoning, secrets and prompt contents.

#### Scenario: Slow lookup
- **WHEN** a turn is retrieving current information before producing text
- **THEN** a visible status indicates retrieval and a stop control remains available

#### Scenario: Terminal transition
- **WHEN** a turn finishes, fails or is cancelled
- **THEN** its progress becomes terminal and no stale busy indicator blocks subsequent sends

### Requirement: Send recovery is bounded
The desktop SHALL release sending state after connection timeouts or cancellation, including before turn creation. Optional memory readiness MUST NOT indefinitely prevent a chat submission. Failed requests SHALL preserve text and attachments for retry.

#### Scenario: Cancel during connection
- **WHEN** the user cancels while hello, readiness or creation is pending
- **THEN** local sending state clears promptly and no late response contaminates a newer conversation

#### Scenario: Failed attachment send
- **WHEN** submission fails with selected attachments
- **THEN** text and attachments remain available for retry and do not silently become a reminder write
