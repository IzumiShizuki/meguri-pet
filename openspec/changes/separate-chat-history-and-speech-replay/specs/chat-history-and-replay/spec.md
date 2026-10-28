## Purpose

Keep desktop conversations unobtrusive by revealing history only on request and allowing users to listen to a completed companion reply again.

## ADDED Requirements

### Requirement: Explicit conversation history
The desktop SHALL show one bounded latest-reply bubble and an input below it when chat is collapsed. Conversation history SHALL be visible only after the user explicitly selects expand chat. Incoming replies, playback, errors, reminders, and attachments SHALL NOT implicitly expand history. Drafts and portrait scale SHALL survive expansion and collapse.

#### Scenario: Default and streaming replies
- **WHEN** a new reply streams while chat is collapsed
- **THEN** the single small bubble updates above the input and history remains hidden

#### Scenario: Explicitly expand and collapse
- **WHEN** the user selects expand chat and then collapse chat
- **THEN** complete history appears and then returns to a latest-reply bubble with the draft preserved

#### Scenario: Functional panel without history
- **WHEN** Todo, reminder review, or attachments open while chat is collapsed
- **THEN** a larger functional bubble shows the tool content and composer without showing historical messages, and closing the tool returns to compact mode

#### Scenario: Failed send while collapsed
- **WHEN** a send fails while chat is collapsed
- **THEN** an error and retry control appear without revealing history or opening a large empty panel

### Requirement: Explicit reply speech replay
The desktop SHALL provide a replay control for completed, nonempty companion replies in the small bubble and expanded history, including Galgame history. User messages, artifacts, failed replies, and unfinished streamed replies SHALL NOT offer replay. Replay SHALL use original speech content and voice style when available, retain volume and speed, preserve automatic speech preferences, and SHALL NOT send a new conversation turn or change history visibility.

#### Scenario: Replay the compact reply
- **WHEN** the user selects replay on the small reply bubble
- **THEN** only that reply is synthesized by the existing local speech service and chat stays collapsed

#### Scenario: Automatic speech is disabled
- **WHEN** the user explicitly replays a completed reply with automatic speech disabled
- **THEN** that reply plays using current volume and speed, and automatic speech remains disabled

#### Scenario: Replay original speech from history
- **WHEN** the user selects an older companion reply with captured speech metadata
- **THEN** its original spoken text, style, and intensity are used and the displayed reply and conversation remain unchanged

#### Scenario: Stop and replace playback
- **WHEN** the user stops playback or selects a different reply
- **THEN** previous playback is cancelled and controls accurately reflect the current selection

#### Scenario: Local speech failure
- **WHEN** local synthesis or playback fails
- **THEN** the selected reply shows failure feedback and can be retried without losing text or the draft

#### Scenario: Live voice call
- **WHEN** a voice call is active
- **THEN** manual replay is disabled so it does not interrupt recognition or the call's reply playback
