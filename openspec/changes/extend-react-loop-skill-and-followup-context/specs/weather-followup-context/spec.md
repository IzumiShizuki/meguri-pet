## Purpose

Make short weather corrections and follow-up questions deterministic by resolving them against the current active conversation branch while retaining an explicit requested forecast date.

## ADDED Requirements

### Requirement: Weather follow-ups inherit the active weather context

The system SHALL inspect the current active session history when classifying a message that has no standalone weather term. If the message is a recognized short follow-up or correction and the nearest relevant user turn is a weather request, the system SHALL classify the new turn as the inherited weather intent.

#### Scenario: Date correction reuses weather intent

- **WHEN** the current message is “我问的是明天来着” and the active branch's nearest relevant user message asks about weather
- **THEN** the current turn is handled by the weather capability instead of the generic LLM fallback

#### Scenario: Unrelated short message remains ordinary conversation

- **WHEN** a message has no weather terms and the active branch has no recent weather request
- **THEN** the message is not classified as weather and the weather provider is not contacted

### Requirement: Explicit date expressions take precedence over inherited dates

The system SHALL preserve an explicit relative or absolute date expression in the current message; when the current message contains no date expression, it SHALL inherit the date expression from the nearest relevant weather user turn.

#### Scenario: Follow-up keeps tomorrow

- **WHEN** the prior weather request asks about tomorrow and the current follow-up only says “我问的是明天来着”
- **THEN** the weather provider receives tomorrow's forecast date

#### Scenario: New explicit date replaces the prior date

- **WHEN** the prior weather request asks about tomorrow and the current weather follow-up explicitly asks about the day after tomorrow
- **THEN** the weather provider receives the day-after-tomorrow forecast date

### Requirement: Branch isolation is preserved

Inherited weather context SHALL come only from the active path of the requested user/client/session scope and SHALL never use messages from a sibling branch or another session.

#### Scenario: Sibling branch cannot supply weather context

- **WHEN** a sibling branch contains a weather request but the active branch does not
- **THEN** a standalone non-weather message on the active branch is not classified as weather
