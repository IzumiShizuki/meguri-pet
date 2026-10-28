## Purpose

Give users direct control over when their desktop companion speaks or acts, with explicit local follow-ups that respect attention and reversible presentation preferences.

## ADDED Requirements

### Requirement: Attention-aware notice delivery
The desktop SHALL apply one policy to unsolicited news, reminders and local commitment follow-ups. It SHALL support explicit focus and off/low/normal/high initiative, bound and expire deferred notices, and defer interruptions during focus or active conversation/playback. Speech SHALL respect mute, volume and surface eligibility. User-requested reply text SHALL remain available during focus.

#### Scenario: Focus defers a notice
- **WHEN** a notice arrives during focused mode or active conversation
- **THEN** it is queued without starting speech or motion and re-evaluated when the desktop becomes available

#### Scenario: Muted delivery remains quiet
- **WHEN** the desktop is available but speech is muted or disabled for the current surface
- **THEN** an eligible notice can be shown as text without audio

#### Scenario: Initiative off
- **WHEN** initiative is off and unsolicited news arrives
- **THEN** the news does not interrupt, while explicitly confirmed local commitments remain eligible for quiet follow-up

### Requirement: Explicit local commitment lifecycle
Users SHALL be able to confirm a local commitment with a future due time, see its state, complete it or cancel it. A due pending commitment SHALL produce at most one automatic follow-up; completed or cancelled commitments SHALL NOT produce queued follow-ups. The UI SHALL state that the desktop must be running. Disabling history storage SHALL remove persisted commitment content.

#### Scenario: Due commitment is followed up
- **WHEN** a confirmed commitment becomes due with the desktop running and attention policy permits delivery
- **THEN** its follow-up is shown and recorded so subsequent ticks or reloads do not repeat it

#### Scenario: Cancellation while deferred
- **WHEN** a user cancels a commitment before its deferred follow-up is delivered
- **THEN** the deferred follow-up is removed

#### Scenario: Storage consent withdrawal
- **WHEN** history saving is disabled
- **THEN** previously persisted local commitment content is cleared and further content is kept only in memory

### Requirement: Reversible presentation preferences
The desktop SHALL validate and persist companion preferences without invalidating existing preference documents. Users SHALL be able to reset presentation choices and undo the last presentation preference change; these choices SHALL NOT modify formal memory or training weights.

#### Scenario: Reset and undo
- **WHEN** a user changes or resets a presentation preference and then chooses undo
- **THEN** the preceding validated choice is restored

#### Scenario: Existing preference migration
- **WHEN** an existing or malformed preference document lacks valid companion fields
- **THEN** bounded valid defaults are used and unrelated preferences are preserved
