## Purpose

为 Meguri 提供带来源、置信度和有效期的屏幕语义事实，并以可解释的语义差异驱动后续记忆与主动表达，避免把连续原始截图当作长期上下文。

## ADDED Requirements

### Requirement: Produce structured screen semantic context

The system SHALL represent an observation result as structured semantic context containing the observed application, activity or task, meaningful content, signals, observation time, source, confidence, and expiration time. The context MUST identify the attached window or observation scope without requiring raw pixels to be retained.

#### Scenario: Observation provider returns a usable result

- **WHEN** an allowed observation provider returns a result for the attached window
- **THEN** the system creates a screen semantic context with source, timestamp, confidence, and expiration metadata
- **AND** the context can be distinguished from ordinary conversation history

#### Scenario: Observation result has insufficient confidence

- **WHEN** a provider cannot produce a reliable semantic interpretation
- **THEN** the system marks the result unavailable or below the configured confidence threshold
- **AND** it does not present the result as a current fact

### Requirement: Compute meaningful semantic differences

The system SHALL compare the latest eligible semantic context with the previous context for the same observation scope and SHALL report meaningful changes in activity, content, or signals. Unchanged or low-value presentation changes MUST NOT be reported as a new semantic event.

#### Scenario: A new error appears

- **WHEN** the current context contains an error signal that was absent from the previous context
- **THEN** the system reports a semantic difference describing the newly appearing error state

#### Scenario: The same activity continues

- **WHEN** the current context describes the same activity and materially equivalent content as the previous context
- **THEN** the system reports no meaningful semantic difference

#### Scenario: An error disappears

- **WHEN** an error signal present in the previous context is absent from the current context
- **THEN** the system reports that the error state has ended

### Requirement: Enforce context freshness with TTL

The system SHALL treat a screen semantic context as current only before its expiration time. After expiration, the context SHALL be classified as recent history or unavailable and MUST NOT be used as an unqualified current fact.

#### Scenario: Context is within TTL

- **WHEN** the current time is before the context expiration time
- **THEN** the context is eligible for current-context consumers

#### Scenario: Context has expired

- **WHEN** the current time reaches or passes the context expiration time
- **THEN** the context is excluded from current-context consumers
- **AND** it may be exposed only as explicitly labeled recent history

### Requirement: Keep raw screen data behind the observation boundary

The system SHALL keep raw screenshots, OCR payloads, or equivalent sensitive capture data behind the local observation boundary unless the user explicitly authorizes a one-time handoff. By default, downstream context and event consumers SHALL receive structured semantic data and provenance rather than continuous raw screen data.

#### Scenario: Normal semantic observation

- **WHEN** a permitted observation is processed without explicit user handoff
- **THEN** downstream consumers receive semantic context and provenance only
- **AND** no continuous raw capture is added to ordinary conversation context

#### Scenario: User explicitly points to a region

- **WHEN** the user explicitly selects and submits a screen region
- **THEN** the selected region may be passed as a one-time high-priority context
- **AND** the authorization does not grant ongoing observation permission

### Requirement: Fail closed when observation is denied or unavailable

The system SHALL produce no current screen semantic context when the observation policy denies access, the attached window is unavailable, or the provider fails. The failure state SHALL retain a non-sensitive reason for diagnostics.

#### Scenario: Provider fails

- **WHEN** the observation provider times out or returns an error
- **THEN** the system returns an unavailable result with a diagnostic reason
- **AND** no stale context is promoted to current solely because the provider failed
