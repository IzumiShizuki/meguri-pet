## Why

P2 companion behavior currently lacks a shared attention policy and explicit commitment follow-up. Desktop one-shot effects are only tracked in memory, while the 2,723-line TurnOrchestrator and bootstrap-era documentation make runtime changes difficult to assess.

## What Changes

- Add a local companion director with explicit focus, four initiative levels, speech mute/surface policy, cooldowns, and bounded deferred notices.
- Add user-confirmed local commitments with due-time follow-up, completion/cancellation, and reversible presentation preferences. Follow-up requires a running desktop and is distinguished from TimePrism server reminders.
- Coordinate text, expression and speech using a session-scoped durable playback ledger; interrupted or already claimed automatic effects cannot replay after reconnect/reload. Explicit replay remains available.
- Extract cohesive response generation and supervision responsibilities from TurnOrchestrator without changing the public protocol, ordering, cancellation, training, or recovery contracts.
- Reconcile root/core/desktop documentation with launcher configuration and record automated versus manual/resource-dependent acceptance accurately.

## Capabilities

### New Capabilities

- `attention-aware-companion`: Focus/initiative policy, explicit local commitments and reversible presentation choices.
- `durable-desktop-performance`: Bounded persistent effect claims, cancellation-safe presentation timing and intentional replay.

### Modified Capabilities

None. Main specs are empty; existing completed change artifacts remain historical evidence.

## Impact

Native desktop preferences, settings, conversation and presentation stores, speech playback, proactive news/reminder integrations; Java turn runtime and its tests; root/core/desktop README and a Chinese P2 delivery report. No new dependencies or remote infrastructure changes are required. P1 training, retrieval, continuous ASR and streaming TTS remain separate work.
