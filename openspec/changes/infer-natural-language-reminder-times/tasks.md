## 1. Reminder Time Inference

- [x] 1.1 Infer the next future occurrence for requests that omit a date, using Asia/Shanghai wall time and advancing to the next day only when needed.
- [x] 1.2 Resolve bare 1–12 hours to the earliest future AM/PM candidate while preserving explicit day-part and 24-hour interpretation.
- [x] 1.3 Keep explicit dates fixed, route inferred values through the existing review phase, and preserve safe retry behavior.
- [x] 1.4 Infer a missing clock using a sleep-specific 12:00 default or the next half-hour, raise the Todo surface, and auto-confirm only when the connected account can safely accept the write.

## 2. Contract Validation

- [x] 2.1 Validate the change artifacts against the OpenSpec schema and ensure their scenarios match the implemented defaults.
