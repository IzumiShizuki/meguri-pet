## Why

The weather tool currently answers a weather request as though it were always for today, even when the user asks for tomorrow. This makes planning-oriented weather questions unreliable despite the upstream forecast already containing daily future data.

## What Changes

- Add date-aware weather queries for natural-language relative dates: today, tomorrow, and the day after tomorrow.
- Accept an explicit ISO-8601 calendar date when it is present in a weather request.
- Return the forecast for the requested calendar day, including a clear date label, while preserving the current response for an unspecified date.
- Reject dates outside the forecast provider's returned range with a clear, non-fabricated response.

## Capabilities

### New Capabilities

- `future-weather-queries`: Resolve a weather request's date and provide the corresponding daily forecast without changing the user's saved location.

### Modified Capabilities

- None.

## Impact

- `java/meguri-core` weather conversation, service, controller, and Open-Meteo gateway paths.
- Existing weather tool prompts and direct responses used by desktop and API clients.
- Weather unit and integration tests; no new external provider or credential is required.
