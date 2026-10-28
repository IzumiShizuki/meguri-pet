## Why

The native desktop cannot use the existing reply-comparison feedback path, consented feedback cannot become reviewed training data, and the local Java launcher still defaults to volatile Turn state. Complete the three P0 items identified on 2026-10-08 while applying the existing Notion B training baseline, section 8 of the 2026-09-17 learning supplement, and the recoverable Turn Runtime specification.

## What Changes

- Add optional native reply comparisons and behavior corrections with explicit training consent, saved receipts and withdrawal.
- Make comparison recovery and feedback submissions durable, scoped and idempotent without changing formal memory or automatically training a model.
- Add an offline feedback import, redaction, review, deduplication and grouped dataset export path compatible with the existing SFT trainer; keep preference-pair exports separate.
- Wire an explicit PostgreSQL runtime configuration and verify completed-event replay, interrupted generation, session recovery and background-job retry after reconstruction/restart.
- Publish the existing main-flow, ReAct, memory, learning and voice explanations plus before/after implementation evidence to the user's existing Notion hierarchy.

## Capabilities

### New Capabilities

- `native-training-feedback`: Optional scoped native feedback, explicit consent, durable receipts, withdrawal and restart recovery.
- `reviewed-feedback-datasets`: Deterministic, provenance-bearing exports of approved feedback with consent, redaction and train/validation separation.
- `durable-local-runtime-recovery`: Explicit durable local runtime selection and demonstrated Turn, context and job recovery.

### Modified Capabilities

None. Existing formal-memory authorization, immutable canonical datasets, provider selection, model registration gates and adapter replay rules remain authoritative.

## Impact

Native Electron/Vue frontend, shared TypeScript protocol/SDK, desktop loopback gateway, Java training/runtime components, local runtime configuration, Python offline training tooling, tests and project documentation. Reuse the existing toolchains and PostgreSQL implementations. Preserve the pre-existing dirty worktree. No model download, real GPU training, registry promotion, deployment, destructive database operation or live Core restart is implicit in dataset preparation and isolated recovery validation.
