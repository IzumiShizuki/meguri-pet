## Purpose

Keep text, expression and speech presentation consistent through cancellation, reconnect and desktop restoration, while allowing deliberate user replay of an earlier reply.

## ADDED Requirements

### Requirement: Durable automatic effect claims
The desktop SHALL persist bounded session-scoped claims before automatic audio or one-shot action effects. Already claimed effects SHALL NOT automatically replay after SSE reconnect or renderer reload. Ledger entries SHALL contain receipt metadata rather than speech text, and failure to persist a new claim SHALL skip the automatic effect while preserving reply text.

#### Scenario: Reconnect and reload
- **WHEN** the same automatic effect is received after reconnect or reload
- **THEN** its audio or one-shot action is not executed a second time

#### Scenario: Session isolation
- **WHEN** a different session receives a distinct valid effect
- **THEN** prior session receipts do not incorrectly suppress it

#### Scenario: Storage failure
- **WHEN** an automatic effect claim cannot be saved
- **THEN** the effect is skipped and the text reply remains usable

### Requirement: Cancellation-safe presentation timing
Text deltas SHALL render without awaiting speech. Speech-linked speaking state and cues SHALL reflect actual playback, and cancellation or replacement SHALL prevent stale asynchronous audio/cue callbacks from changing the new presentation. Recovered snapshots SHALL restore static state without restarting one-shot motion.

#### Scenario: Slow synthesis
- **WHEN** speech synthesis is delayed
- **THEN** text delivery continues and speaking state stays false until audio begins

#### Scenario: Replacement and late callback
- **WHEN** an old request is cancelled or replaced and later finishes asynchronously
- **THEN** it cannot restart audio, apply stale cues or clear the replacement's speaking state

#### Scenario: Snapshot recovery
- **WHEN** an expression snapshot is restored
- **THEN** static expression is restored without replaying its one-shot action

### Requirement: Deliberate manual replay
Users SHALL be able to replay a completed reply with a fresh playback identity even when automatic speech is disabled or the original effect is already claimed. Stop/replacement SHALL remain effective and SHALL NOT create a new chat turn.

#### Scenario: Replay after automatic claim
- **WHEN** a user replays an already delivered completed reply
- **THEN** a fresh intentional playback occurs without resubmitting chat or changing saved speech preferences
