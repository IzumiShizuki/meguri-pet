## Purpose

Recover only the bounded original history needed to answer a THINK query while preserving explicit-reference semantics, token budgets, active-branch isolation, and exact context replay.

## ADDED Requirements

### Requirement: Automatic rehydration is THINK-only
The system SHALL automatically rank structured facts and rehydrate source windows only for THINK context policy, while FAST and AGENT SHALL not automatically rehydrate facts. Explicit QUOTE and TOPIC_LINK references SHALL retain their existing behavior in every mode.

#### Scenario: THINK query matches an older fact
- **WHEN** a THINK turn asks about a detail represented by an active structured fact
- **THEN** the context includes a bounded REHYDRATED block for the matching source span if the rehydration budget permits

#### Scenario: FAST query matches an older fact
- **WHEN** a FAST turn asks about a detail represented only in compressed history
- **THEN** no automatic rehydration is added and the existing compact context path is used

#### Scenario: Explicit quote is submitted in FAST
- **WHEN** a FAST turn contains a valid QUOTE or TOPIC_LINK reference
- **THEN** the reference resolver restores its existing bounded window independently of automatic rehydration

### Requirement: Automatic rehydration is bounded and active-branch safe
Automatic rehydration SHALL enforce independent limits of at most three facts, three source spans, 1024 rehydrated tokens, and two messages before and after each selected source by default. It SHALL reject sources not present on the current active path and SHALL deduplicate overlapping spans.

#### Scenario: More facts match than the budget allows
- **WHEN** more than three facts match the current query
- **THEN** only the highest-ranked facts within the source-span and token limits are selected

#### Scenario: A matching fact points to a sibling branch
- **WHEN** a structured fact references a message outside the active path
- **THEN** that fact is ignored and no sibling content enters the ContextBundle

### Requirement: Rehydration decisions are traceable and budgeted
The system SHALL record each automatic rehydration decision with the fact ID, source IDs, score, reason, selected state, and token count, and SHALL treat automatic rehydration as optional content that may be removed before required context when the global budget is exceeded.

#### Scenario: Automatic content exceeds the hard budget
- **WHEN** the assembled context exceeds the hard threshold after optional content is considered
- **THEN** automatic rehydration is removed before required current input or exact reference content, and the removal is recorded as a truncation

#### Scenario: Context is replayed
- **WHEN** a context trace is replayed after the conversation changes
- **THEN** the saved bundle and rehydration decisions are returned exactly without selecting history again

### Requirement: Read-model failure falls back to authority
The system SHALL use a verified conversation read model for ordinary context assembly when available, and SHALL fall back to the complete authoritative Harness when the read model is missing, stale, malformed, or incompatible with the graph revision or source digest.

#### Scenario: Branch switch invalidates the projection
- **WHEN** a branch switch, resume, edit, reference, or stale summary changes the context revision
- **THEN** the system rebuilds from the Message DAG and refreshes the derived projection
