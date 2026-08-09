## Purpose

Minimize time from Provider first token to visible client text while retaining durable ordered events, deterministic replay, terminal consistency, and side-effect idempotency.

## ADDED Requirements

### Requirement: The first semantic delta bypasses coalescing delay
The first non-empty Provider token SHALL immediately form a `TextDeltaChunk` regardless of the later-token time or character thresholds, and SHALL NOT be replaced with non-semantic placeholder text.

#### Scenario: Provider emits one token then pauses
- **WHEN** the first non-empty token arrives before a long pause
- **THEN** that token is emitted as the first chunk without waiting for the configured subsequent-chunk delay

### Requirement: Persistence precedes broadcast
Each text chunk SHALL be appended durably as a monotonic Turn event before it is broadcast and flushed to clients.

#### Scenario: First event append fails
- **WHEN** persistence of the first delta fails
- **THEN** the server does not broadcast that unpersisted delta as a successful event

### Requirement: Subsequent deltas may be micro-batched
After the first chunk, the system SHALL apply configurable maximum delay and character thresholds and SHALL record why each chunk flushed.

#### Scenario: Character threshold is reached
- **WHEN** pending subsequent text reaches the configured character threshold before the time threshold
- **THEN** one recoverable chunk flushes with the `MAX_CHARACTERS` reason

### Requirement: Replay reconstructs identical text and terminal state
Delta coalescing SHALL preserve monotonic sequence, exact text concatenation, cursor replay, cancellation, failure, and terminal semantics.

#### Scenario: Client reconnects after two chunks
- **WHEN** the client resumes from the last persisted sequence
- **THEN** replay does not re-run the Provider or duplicate text, tools, TTS, animation, or other ONCE side effects

### Requirement: SSE and clients expose first-visible boundaries
The server SHALL use event-stream/no-cache/no-buffer headers and per-event delivery, and character-stream clients SHALL render semantic deltas without waiting for punctuation, TTS, animation, or a complete sentence.

#### Scenario: Website receives the first delta
- **WHEN** the browser receives a persisted `text.delta`
- **THEN** it updates visible text immediately and records receive/render timestamps separately

### Requirement: Platform message latency is not character TTFT
Clients that cannot display character streaming SHALL report a distinct first-platform-message metric rather than mixing it with Website/AIRI first-render TTFT.

#### Scenario: AstrBot batches for platform delivery
- **WHEN** AstrBot sends a short aggregated platform message
- **THEN** the sample is labeled `first_platform_message_latency` and excluded from character-level TTFT distributions
