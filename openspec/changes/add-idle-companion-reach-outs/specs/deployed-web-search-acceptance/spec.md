## Purpose

Ensure the deployed desktop and server use an available search endpoint and prove useful retrieval with relevant, accessible source links rather than counting HTTP success alone.

## ADDED Requirements

### Requirement: Validated search deployment
The installed local launcher and remote deployment configuration SHALL enable web search with the validated Bing regional endpoint `https://cn.bing.com/search`. Deployments MUST preserve existing credentials, persistence and non-search configuration, and SHALL retain a rollback version before replacing the active server Core.

#### Scenario: Search endpoint returns unrelated results
- **WHEN** the existing search entry point returns HTTP 200 with irrelevant results for a controlled query
- **THEN** acceptance MUST fail and the configured replacement SHALL be checked against the same topic before activation

### Requirement: Live network evidence
Acceptance SHALL exercise the real model and web retrieval through both the server Core and the desktop gateway. It MUST verify a completed answer, a positive web retrieval lane, relevant source links, and an independently accessible supporting public page. Tests MUST use dedicated sessions without formal-memory writes or real platform messages.

#### Scenario: Search and answer succeed
- **WHEN** an explicit public-document query completes through the deployed runtime
- **THEN** the evidence records the retrieval count, answer, cited URLs and an accessible relevant public source

#### Scenario: Stream closes before terminal event
- **WHEN** the desktop SSE stream closes before its terminal event is observed
- **THEN** acceptance SHALL verify completion using the same reconnect/snapshot contract available to the desktop client and record which path supplied completion
