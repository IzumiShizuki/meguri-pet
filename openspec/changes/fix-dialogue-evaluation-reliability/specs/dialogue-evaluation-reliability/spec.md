## Purpose

Ensure isolated dialogue turns follow explicit retrieval intent, preserve current input and corrections, and recover the same durable result without disclosing another user's settings.

## ADDED Requirements

### Requirement: Explicit and bounded web retrieval
The system SHALL honor explicit search and official-source verification requests, preserve offline instructions, transmit the intended subject, reject unrelated results and attempt at most one bounded recovery query.

#### Scenario: Wrapped request
- **WHEN** a request includes introductory wording before an explicit search subject
- **THEN** the search receives the subject and material qualifiers without that introductory wording

#### Scenario: Offline or fictional context
- **WHEN** the user prohibits network use or recalls a fictional project without requesting external information
- **THEN** no web search is performed

#### Scenario: Unrelated results
- **WHEN** search results lack the subject's meaningful anchors
- **THEN** they are not presented as evidence and recovery stays within one additional request and the original deadline

### Requirement: Weather intent and location isolation
The system SHALL keep documentation and code questions out of the forecast shortcut and SHALL NOT silently use global location settings for an unscoped conversation.

#### Scenario: API documentation
- **WHEN** the user asks for weather API documentation
- **THEN** no forecast is requested

#### Scenario: Missing location
- **WHEN** a weather request has not explicitly selected an available location
- **THEN** the reply requests a location without exposing the configured place or querying its forecast

### Requirement: Current turn and durable completion
The system SHALL associate input, context, output and terminal replay with the same turn, preserve serial execution, and drain persisted terminal events before ending a stream.

#### Scenario: Failure then new input
- **WHEN** one turn fails and a new turn is submitted
- **THEN** the provider receives the new input as the current question and recovery never substitutes the earlier turn's answer

#### Scenario: Completion during polling
- **WHEN** a turn completes between an event poll and an inactivity check
- **THEN** its terminal event remains available on the live stream and reconnect

### Requirement: Source facts and conversational preferences
The system SHALL distinguish chronological user facts from derived assistant claims and instruct response generation to apply the latest applicable correction, recompute dependent values and respect current conversational preferences.

#### Scenario: Corrected source value
- **WHEN** a user changes a source amount after an assistant calculated a balance
- **THEN** the prompt identifies the latest user facts as authoritative and requires recalculation rather than reuse of the prior derived balance

### Requirement: Explicit training capture
The system SHALL keep formal memory consent independent of training capture and SHALL NOT generate or archive unsolicited training alternatives when training mode is false.

#### Scenario: Ordinary isolated chat
- **WHEN** training mode is false
- **THEN** no alternative-generation call, candidate-ready event or training archive entry is produced
