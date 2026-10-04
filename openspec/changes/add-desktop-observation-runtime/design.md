## Context

The Java runtime is the current authoritative implementation for Persona, time state, context assembly, and durable event handling. The desktop Electron shell currently renders the pet but does not own a native window attachment or screen capture boundary. Existing `screen_context` flags are capability negotiation only, and `ContextBundle` is intentionally conversation-oriented.

This change therefore adds a pure, local-first observation domain layer without pretending that a real Windows capture provider already exists. See `proposal.md` for the motivation and the capability specs for observable behavior.

## Goals / Non-Goals

**Goals:**

- Provide deterministic window attachment state transitions and privacy decisions.
- Represent screen observations as immutable semantic facts with provenance, confidence, and TTL.
- Compute meaningful semantic differences without storing continuous raw screen data.
- Provide a provider boundary and deterministic mock implementation for tests and future Windows/PixPin/Screenpipe adapters.
- Keep observation state separate from ordinary user turns and conversation history.

**Non-Goals:**

- No native HWND discovery, Windows Graphics Capture, PixPin, OCR, VLM, or Screenpipe integration in this change.
- No autonomous pet movement, proactive speech, Watch lifecycle, or long-term memory writes.
- No automatic enabling of `screen_context` or `screen_read` in the existing adapter handshake.
- No addition of raw screenshots or OCR payloads to `ContextBundle`.

## Decisions

### 1. Keep the observation domain in Java Core

Add a small `com.meguri.core.observation` domain area with immutable Java records and pure services. Java Core already owns deterministic runtime state and durable event policy, so placing the policy and semantic transformations there avoids making Electron or a future LLM the authority for privacy and freshness.

Alternative considered: implement all state in the Electron renderer. Rejected because renderer state is not authoritative across clients, is harder to audit, and would encourage capture data to be mixed with presentation concerns.

### 2. Model attachment as a reducer, not a background mover

Represent the current user/client attachment as a state containing one `ObservedWindow`. Attach, update, restore, and detach operations return new state. Geometry updates are accepted only for the same window identity; the reducer never selects another window or issues a movement command for the pet.

Alternative considered: a polling service that searches for the best nearby window. Rejected because it violates the explicit user-placement boundary in the baseline and makes privacy behavior implicit.

### 3. Use conservative local privacy evaluation

`ObservationPolicy` contains an enabled flag, privacy mode, application deny-list, title deny-list, and sensitive-window defaults. Matching is case-insensitive and deterministic. A denied request returns a reason and stops before the provider is called.

Alternative considered: let the provider or model decide whether a captured frame is safe. Rejected because privacy must be enforced before capture and must not depend on model interpretation.

### 4. Keep semantic context independent from conversation blocks

`ScreenContext` is a typed observation result, not a `ContextBundle.Block` in this first slice. It carries scope, source, observation time, expiration, confidence, activity, content, and signals. A later change can add a deliberate context projection with its own token budget and provenance rules.

Alternative considered: immediately add screen data to `RECENT_RAW`. Rejected because that would make screen facts indistinguishable from user messages and would bypass TTL and privacy-specific handling.

### 5. Make freshness and diff computation deterministic

The context service accepts an explicit `Instant` and never reads system time internally. A context is current only before its expiration; expired data is classified as recent history. Semantic diff compares the same observation scope, normalizes low-value whitespace differences, and reports activity/content/signal changes only when they are meaningful.

Alternative considered: compare serialized JSON or screenshots. Rejected because presentation-level changes create noisy events and screenshot comparison is expensive and privacy-sensitive.

### 6. Define a provider boundary without enabling capture

`ScreenObservationProvider` returns either a semantic result or a non-sensitive unavailable reason. A deterministic mock provider will be used by tests and future local-agent integration. The first change does not alter the existing adapter permission defaults; a real provider must be introduced only together with a dedicated local permission and audit design.

## Risks / Trade-offs

- [Risk] A simple string comparison may miss semantically equivalent content or over-report meaningful changes. → Normalize whitespace and keep the diff contract explicit; improve semantic normalization in a later provider-specific change.
- [Risk] The first slice cannot demonstrate real screen observation. → Include a mock provider and state-level tests so the integration contract is executable before native capture work begins.
- [Risk] A TTL that is too long exposes stale facts; one that is too short reduces usefulness. → Make TTL part of the policy/context data and require callers to pass an explicit clock; tune defaults after observing metrics.
- [Risk] Adding a protocol shape without a live endpoint could be mistaken for enabled screen access. → Keep the protocol types marked as observation-domain events, leave `screen_context`/`screen_read` disabled, and do not wire them into the normal turn request.

## Migration Plan

1. Add the Java observation domain and tests with no runtime wiring that can capture the screen.
2. Add serializable TypeScript observation event types as an integration contract only.
3. Run the existing Java test suite and protocol type checks.
4. In a later change, introduce a local Windows provider behind the same interfaces and explicitly expand the adapter permission handshake.

Rollback is removing the new observation package and protocol types; existing turn, Persona, AIRI, and startup paths remain unchanged.
