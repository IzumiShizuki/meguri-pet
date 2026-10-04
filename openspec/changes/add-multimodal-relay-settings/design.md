## Context

See `proposal.md` for motivation and `specs/desktop-multimodal-relay-settings/spec.md` for observable behavior. The desktop already has a frameless settings window, a sandboxed preload bridge, trusted IPC validation patterns and a repository-external DeepSeek key. Java Core already selects `MEGURI_LLM_MULTIMODAL_FALLBACK_*` only for resolved image/PDF content. The missing seam is a safe desktop-owned configuration store that the local launcher can read before Core is created.

## Goals / Non-Goals

**Goals:**

- Extend the incumbent settings visual system instead of introducing a second configuration UI.
- Keep secrets out of renderer persistence, normal desktop preferences, command lines and logs.
- Verify actual `image_url` acceptance with a bounded user-triggered probe.
- Reuse the current deterministic multimodal fallback route and preserve text behavior.

**Non-Goals:**

- Creating provider accounts or API keys, discovering paid providers automatically, or monitoring provider health in the background.
- Hot-reloading Java model clients or silently restarting the active desktop stack after Save.
- Claiming that a text-only relay model has vision merely because `/models` lists it.
- Implementing a two-stage vision-caption-to-DeepSeek-Pro pipeline in this configuration-entry change.

## Decisions

### Store relay configuration outside desktop preferences

Use a small versioned JSON file under `D:\environment\config\meguri` for `enabled`, `baseUrl`, `model` and timestamps, and a separate key file under `D:\environment\secrets\meguri`. Paths can be overridden for tests and future portability. The main process returns only `apiKeyConfigured: boolean`; it never hydrates the renderer with the stored key. Reusing `desktop-state.json` was rejected because that object is broadcast to renderer windows and is intended for non-sensitive presentation state.

### Keep all validation in a focused main-process module

A pure configuration module normalizes URLs and model IDs, rejects remote plain HTTP while allowing HTTPS and loopback HTTP for local relays, enforces conservative field lengths, performs atomic-enough same-directory writes, and exposes sanitized read/save results. Renderer validation improves immediacy but is not authoritative. IPC handlers also retain the existing trusted-renderer check.

### Test the capability that Meguri actually needs

The test action sends a tiny embedded PNG and short prompt to `<baseUrl>/chat/completions` with the selected model, a low output limit and a bounded timeout. A successful non-empty assistant response proves the endpoint accepted OpenAI-compatible image content. `/models` alone was rejected because DeepSeek V4 demonstrates that a listed chat model can still reject `image_url`. Provider error text is reduced to a bounded user-facing message and neither request headers nor keys are logged.

### Apply changes on the next trusted Core start

`start-meguri-core.ps1` reads the JSON and verifies the external key file before setting `MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL`, `...BASE_URL`, `...API_KEY_FILE` and disabled thinking. Invalid, disabled or incomplete settings emit a warning at most and leave fallback variables empty; the text route remains `deepseek-v4-flash`. Hot replacement was rejected because current model clients are constructed once and replacing them mid-session would make routing and conversation failures difficult to reason about.

### Extend the existing Operate-mode settings surface

Add one “多模态” entry beside the existing service controls. Its detail uses the current card, label and button vocabulary, with one compact route preview (`图片 / PDF → 视觉中转站`) as the distinctive status element. Fields remain a single scan path, dark-mode colors reuse existing tokens, dynamic status uses an `aria-live` region, and Save/Test disable during their own in-flight operation to prevent duplicate requests. No new typeface, gradient system or decorative panel is introduced.

## Risks / Trade-offs

- [Repository-external files are readable by the current Windows user] → Keep them out of renderer state and logs, use the established secret root, create files with restrictive mode where supported, and document that OS account access remains the trust boundary.
- [A compatible endpoint may accept the test but behave differently for large screenshots] → Keep existing per-attachment/aggregate bounds and describe the test as image-input compatibility, not a quality guarantee.
- [Provider-specific request extensions differ] → Send only baseline OpenAI-compatible fields and allow users to configure any compatible Base URL/model; expose precise rejection text without adding speculative retries.
- [Save does not affect the running Core immediately] → Show persistent restart-required feedback and verify on the next full local restart.
- [The fixed local environment root limits portability] → Isolate path resolution and accept environment overrides so packaging work can move storage later without changing the UI contract.

## Migration Plan

1. Add the relay configuration module and IPC/preload contract with tests; absent files normalize to an unconfigured disabled state.
2. Extend the incumbent settings surface and preview bridge, then run unit, preload, type and build checks.
3. Make the trusted Core launcher consume only complete enabled configuration.
4. Restart the local stack and visually confirm the settings entry and sanitized empty state; no external relay request occurs until the user configures one and presses Test.
5. Roll back by removing the new IPC/UI and launcher JSON load. Existing text and attachment validation continue; image/PDF turns return the current missing-vision configuration message.
