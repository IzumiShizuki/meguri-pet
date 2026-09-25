## 1. Date-aware weather request handling

- [x] 1.1 Add a deterministic regression test showing that a `明天` weather request selects tomorrow rather than today's forecast.
- [x] 1.2 Resolve bounded relative date phrases and an explicit ISO-8601 date in the saved location timezone.
- [x] 1.3 Route the resolved date through the weather conversation service to the date-aware gateway and direct response context.

## 2. Forecast safety and presentation

- [x] 2.1 Preserve today's current behavior for requests without an explicit date and prioritise explicit ISO dates over relative phrases.
- [x] 2.2 Return a clear unavailable result for a requested date absent from the provider forecast without substituting another date.

## 3. Verification

- [x] 3.1 Add unit coverage for Chinese and English relative dates, ISO dates, timezone boundaries, and unavailable dates.
- [x] 3.2 Run the targeted weather tests and the Java module verification, then validate this OpenSpec change strictly.
