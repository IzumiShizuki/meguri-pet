## Context

The existing weather path already carries a target-date-capable gateway and daily forecast payload, but conversation intent currently discards the requested date and most conversational queries therefore default to today. See `proposal.md` for the motivation and `specs/future-weather-queries/spec.md` for observable behavior.

## Goals / Non-Goals

**Goals:**

- Resolve relative and ISO-8601 dates in the saved location's timezone before fetching weather.
- Carry the resolved date through the conversation service to the existing forecast gateway.
- Make direct tool responses and model context describe the selected day consistently.
- Keep a deterministic unit-test seam by injecting a clock and gateway fixture.

**Non-Goals:**

- Supporting arbitrary natural-language dates, another weather provider, multi-day tabular forecasts, or a location change inferred from chat text.
- Changing scheduled weather notices, rain alert policy, or the saved-location API.

## Decisions

### Resolve dates in the conversation boundary

The conversation weather service will parse date language and resolve it using the saved location's timezone. This keeps request interpretation next to intent classification and lets the gateway remain a provider-focused adapter. Passing raw text to the gateway was rejected because it couples provider code to user language.

### Preserve a typed requested-date value through the weather path

The resolved `LocalDate` will be supplied to the existing date-aware forecast operation and included in the weather context used for direct replies. The default path will continue to resolve to today. Re-parsing text in every caller was rejected because it would create inconsistent results between direct and augmented replies.

### Prefer explicit ISO dates over relative date words

When a request contains both an ISO date and relative language, the explicit date wins because it is unambiguous and user-authored. The parser will only recognise a bounded set of relative forms; unknown phrases will keep the current today default instead of guessing.

## Risks / Trade-offs

- [Provider returns no requested daily entry] → Surface the existing unavailable error with the requested date; never substitute today's record.
- [Timezone crosses a local date boundary] → Use the saved weather location timezone and an injected clock in tests.
- [Model-generated wording contradicts the selected day] → Use the typed weather context/direct response as the source of date wording rather than asking the model to infer it.

## Migration Plan

1. Add parser and service tests that reproduce the current tomorrow-to-today fallback.
2. Route the resolved date to the existing date-aware gateway operation and update user-visible formatting.
3. Run module tests and the existing weather controller test suite.
4. Deploy normally; rollback is safe because unspecified weather requests remain today's forecast.
