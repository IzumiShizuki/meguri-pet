## Purpose

Keep topic-boundary decisions explainable and reversible by separating them from retrieval query rewriting, so retrieval wording cannot silently change conversation history selection.

## ADDED Requirements

### Requirement: Topic detection is independent from retrieval rewriting
The context pipeline SHALL receive a dedicated topic signal containing a label, confidence, boundary reason, and detector revision, and SHALL NOT use the retrieval rewritten query as the canonical topic hint.

#### Scenario: Retrieval rewrites wording without a topic change
- **WHEN** retrieval changes the query wording for search quality but the topic detector reports the current topic
- **THEN** the context builder uses the topic detector's signal and not the rewritten query as the topic label

#### Scenario: Topic detector is unavailable
- **WHEN** no dedicated topic signal is available
- **THEN** the context builder uses the existing safe general-topic behavior without resetting the active branch

### Requirement: Topic changes remain reversible candidates
The system SHALL preserve candidate, active, and merged topic semantics, and SHALL NOT reset the active session solely because a low-confidence topic signal differs from the current topic.

#### Scenario: Low-confidence topic differs
- **WHEN** a detector reports a different label below the acceptance confidence
- **THEN** the system stores or exposes a candidate without changing the active topic or active message path

#### Scenario: Accepted topic changes
- **WHEN** a topic candidate is explicitly accepted at the configured confidence
- **THEN** the new segment becomes active and the previous active segment remains auditable
