## Purpose

Enable users to ask the weather tool about a near-future or explicitly named calendar day and receive the forecast that corresponds to that date.

## ADDED Requirements

### Requirement: Resolve a requested weather date

The system SHALL interpret a weather request containing `today`, `tomorrow`, `the day after tomorrow`, or their Chinese equivalents as the corresponding calendar date in the saved weather location's timezone. An ISO-8601 date in a weather request SHALL select that exact calendar date. A weather request without a date SHALL retain the existing today behavior.

#### Scenario: Query tomorrow in Chinese

- **WHEN** a user asks `明天天气怎么样`
- **THEN** the system selects the calendar day after today in the saved location's timezone

#### Scenario: Query an explicit date

- **WHEN** a user asks for `2026-08-03` weather
- **THEN** the system selects `2026-08-03` as the requested calendar date

#### Scenario: Query without a date

- **WHEN** a user asks `今天天气怎么样` or omits a date
- **THEN** the system selects today in the saved location's timezone

### Requirement: Return the requested daily forecast

The system SHALL return the weather provider's daily forecast for the resolved requested date, rather than substituting current conditions from today. The response SHALL identify the resolved date with a human-readable date label.

#### Scenario: Tomorrow has different conditions

- **WHEN** tomorrow's daily forecast differs from today's current conditions
- **THEN** the weather response reports tomorrow's forecast and labels it as tomorrow

### Requirement: Handle unsupported forecast dates safely

The system SHALL not fabricate weather data when the requested date is absent from the provider response. It SHALL return a clear unavailable response that identifies the requested date.

#### Scenario: Requested date is outside returned forecast range

- **WHEN** a user requests a date absent from the weather provider response
- **THEN** the system reports that the forecast for that date is unavailable and does not return another day's weather as a substitute
