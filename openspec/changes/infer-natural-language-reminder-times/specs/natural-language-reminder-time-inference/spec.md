## Purpose

Lets users get a useful reminder from ordinary time phrases without answering avoidable date, AM/PM, or clock questions. The Todo surface is raised immediately and a connected, valid one-shot reminder is confirmed automatically.

## ADDED Requirements

### Requirement: Infer the nearest future reminder time
When a one-time reminder has a clear title and clock time but omits its date, Meguri SHALL infer the nearest future occurrence in Asia/Shanghai. For a bare 1–12 hour without a day part, Meguri SHALL consider the AM and PM occurrences and choose the nearest future one. If the user supplied a date or day, Meguri SHALL honor it and SHALL ask for clarification when its specified time is invalid or already past.

#### Scenario: Bare hour shortly after midnight
- **WHEN** it is 00:00 in Asia/Shanghai and the user says “提醒我1点睡觉”
- **THEN** Meguri prepares an editable review draft for today at 01:00 titled “睡觉” without asking for a date or AM/PM

#### Scenario: Bare hour with an afternoon occurrence closer
- **WHEN** it is 10:00 in Asia/Shanghai and the user says “提醒我3点休息”
- **THEN** Meguri prepares an editable review draft for today at 15:00

#### Scenario: No date and today's clock occurrences have passed
- **WHEN** it is 18:00 in Asia/Shanghai and the user says “提醒我3点休息”
- **THEN** Meguri prepares an editable review draft for the nearest future occurrence, tomorrow at 03:00

#### Scenario: Explicit date remains authoritative
- **WHEN** it is 18:00 in Asia/Shanghai and the user says “今天3点提醒我休息”
- **THEN** Meguri asks the user to correct the past time instead of moving the reminder to tomorrow

### Requirement: Raise and complete inferred reminders
An inferred date or time SHALL be shown in the raised Todo surface. When the Shizuki account is connected and the write is unambiguous, Meguri SHOULD confirm the one-shot Todo without a second user click. Account, network, validation, or uncertain-write failures SHALL preserve the raised review/retry state.

#### Scenario: Automatic confirmation for a connected account
- **WHEN** Meguri infers a missing date or AM/PM period
- **THEN** the Todo surface opens and a connected account receives the inferred one-shot Todo without an additional confirmation click

#### Scenario: Missing clock gets a deterministic default
- **WHEN** the user says “提醒我睡觉” at 12:00 in Asia/Shanghai
- **THEN** Meguri creates or previews today at 12:00 and does not ask the user to choose a time
