## 1. Window Observation Domain

- [x] 1.1 Add immutable window identity, geometry, anchor, offset, visibility, attachment status, and user/client scope models under `com.meguri.core.observation`.
- [x] 1.2 Implement deterministic attachment state transitions for attach, same-window geometry update, minimize/unavailable, restore, and detach; reject updates for a different window identity.
- [x] 1.3 Implement observation policy and privacy decision models covering enabled state, privacy mode, application/title deny-lists, sensitive-window defaults, and non-sensitive denial reasons.
- [x] 1.4 Implement an observation status projection suitable for the desktop client without exposing protected window content.

## 2. Screen Semantic Context

- [x] 2.1 Add immutable `ScreenContext` and provider result models containing scope, application, activity, content, signals, source, confidence, observed-at, and expires-at metadata.
- [x] 2.2 Implement explicit-clock freshness classification so expired contexts cannot be used as current facts.
- [x] 2.3 Implement deterministic `SemanticDiff` calculation with normalized content comparison, activity changes, signal additions/removals, and initial-context handling.
- [x] 2.4 Add the `ScreenObservationProvider` boundary, a fail-closed observation pipeline, and a deterministic mock provider; ensure denied requests never invoke the provider.

## 3. Integration Contract

- [x] 3.1 Add serializable TypeScript observation event and payload types for attachment status, screen context, and semantic diff, without adding them to ordinary turn requests.
- [x] 3.2 Export the observation contract from `@meguri/protocol` and document that existing `screen_context`/`screen_read` permissions remain disabled until a later native-provider change.

## 4. Verification

- [x] 4.1 Add Java tests for attachment identity, geometry updates, minimize/restore behavior, and no implicit window switching.
- [x] 4.2 Add Java tests for pause/privacy mode, application/title deny-lists, sensitive defaults, allowed windows, and provider short-circuiting.
- [x] 4.3 Add Java tests for semantic context validation, confidence thresholds, TTL boundary behavior, meaningful diffs, provider failure, and stale-context rejection.
- [x] 4.4 Run the focused Java test suite, the full Java module test suite if stable, and the protocol type/lint checks; record any unrelated baseline failures.
