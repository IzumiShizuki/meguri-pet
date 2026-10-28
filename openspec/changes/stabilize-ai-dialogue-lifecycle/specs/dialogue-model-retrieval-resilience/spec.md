## Purpose

Provide predictable dialogue across independent planner and answer models, retrieval bridge results, dependency failures and later turns, with observable degradation and reproducible local transport tests.

## ADDED Requirements

### Requirement: Planner and answer models have isolated routes
The runtime SHALL send planning requests to the configured planner model and final answer requests to the configured answer model. Planner errors SHALL use bounded recovery without blocking later answers.

#### Scenario: Different models serve one turn
- **WHEN** distinct planner and answer models are configured
- **THEN** planning and answer HTTP requests use their respective configured model identities

#### Scenario: Planner fails then next turn succeeds
- **WHEN** planning fails or times out before an answer
- **THEN** bounded fallback can complete the answer and later turns can use planning again

### Requirement: Input checking precedes the main dialogue
When configured, the runtime SHALL check and clarify initial text through a separate input model before main generation. It MUST retain raw text as the authority for permissions, action intent, history and explicit online-search opt-out, and SHALL fall back to raw text on bounded input-model failure.

#### Scenario: Successful input clarification
- **WHEN** a configured input model returns a valid clarification
- **THEN** the main model receives both the original and separate clarified text and the original stored request remains unchanged

#### Scenario: Unavailable or invalid input model
- **WHEN** input checking times out, fails or returns invalid content
- **THEN** the main flow continues with the original text and the next turn can retry input checking

#### Scenario: Rewrite attempts to change an operation
- **WHEN** the input model introduces an operation, alters an identifier/time/number, or contradicts an explicit restriction
- **THEN** the rewrite is rejected or remains non-authoritative and cannot authorize a tool or change search policy

### Requirement: RAG failures cannot poison later turns
The runtime SHALL bound retrieval work by the turn deadline, preserve successful RAG results only in the matching turn and expose retrieval degradation rather than claiming successful verification.

#### Scenario: Successful and empty retrieval
- **WHEN** retrieval returns relevant items or an empty result
- **THEN** generation receives respectively those items or an explicit empty outcome within the correct session

#### Scenario: Unavailable or malformed bridge response
- **WHEN** retrieval encounters a network error or invalid response
- **THEN** the turn can complete with an observable unavailable result and later turns can retry retrieval

#### Scenario: Late return after timeout or cancellation
- **WHEN** an old retrieval returns after its turn timed out or was cancelled
- **THEN** it cannot change the active turn's context, events or final answer
