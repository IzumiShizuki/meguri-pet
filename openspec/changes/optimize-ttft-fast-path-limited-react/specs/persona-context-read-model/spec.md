## Purpose

Provide versioned rebuildable projections for stable persona and active conversation context while keeping all turn-dynamic signals and authoritative state outside the cache boundary.

## ADDED Requirements

### Requirement: PersonaBaseSnapshot contains only stable versioned state
The system SHALL key `PersonaBaseSnapshot` by user, character, persona revision, relationship version, stable scene version, preference version, and override watermark, and SHALL exclude current input, current intent, retrieval/tool results, selected exemplars, and current `SceneDelta`.

#### Scenario: Current topic changes suddenly
- **WHEN** a user switches from ordinary chat to a code question
- **THEN** the cached base snapshot remains usable while current Turn signals can still trigger code retrieval and different exemplar selection

### Requirement: Authority version changes invalidate snapshots
The system SHALL invalidate and rebuild a base snapshot when any bound authority version changes, and SHALL NOT write snapshot content back into persona, relationship, scene, or preference authority tables.

#### Scenario: Relationship version advances
- **WHEN** an approved relationship transition creates a new authority version
- **THEN** the next Turn does not compose from the old snapshot version

### Requirement: Effective persona is composed per Turn
The system SHALL compose `EffectivePersonaState` from the base snapshot plus current Turn signals, deterministic temporal computation, `SceneDelta`, runtime override, and client capability.

#### Scenario: Time boundary passes while cache remains warm
- **WHEN** the local temporal mode changes between Turns without an authority revision change
- **THEN** the next effective state reflects the new temporal computation rather than the snapshot creation time

### Requirement: Conversation read model exposes the active projection
`ConversationContextReadModel` SHALL include conversation ID, active leaf, active topic segment, latest stable summary, recent message window, open loops, reference index, precomputed token count, and version.

#### Scenario: Ordinary Turn reads current context
- **WHEN** no reference, edit, branch, or stale-summary condition is present
- **THEN** the Turn can obtain the active context with one read-model lookup instead of traversing the complete message DAG

### Requirement: Complex history operations fall back deterministically
The system SHALL use the complete Harness path for QUOTE, RESUME_FROM, TOPIC_LINK, message edit, branch switch, or stale summary and SHALL rebuild the read model from authoritative data afterward.

#### Scenario: Summary becomes stale after branch switch
- **WHEN** the active branch changes and invalidates the latest summary
- **THEN** the Turn uses the full Harness and does not expose the stale summary as stable context

### Requirement: Read-model failure cannot invent authority
When a read model is missing, invalid, or cannot be verified, the system SHALL rebuild from authoritative state or follow the existing safe fallback, and SHALL NOT infer relationship upgrades or formal memory.

#### Scenario: Cached projection has an unknown version
- **WHEN** the projection version cannot be matched to authority revisions
- **THEN** the projection is rejected and no unverified state enters the prompt
