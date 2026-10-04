## 1. Trusted relay configuration

- [x] 1.1 Add a tested main-process relay configuration module that normalizes missing state, validates HTTPS/loopback URLs and model IDs, separates secret/non-secret files, retains an existing secret on blank updates, and returns only sanitized state.
- [x] 1.2 Add a bounded OpenAI-compatible image-input probe with timeout and actionable sanitized errors, without logging or returning API keys.
- [x] 1.3 Add trusted IPC handlers, preload methods, shared TypeScript contracts and preview stubs for relay get/save/test operations.

## 2. Existing settings-surface extension

- [x] 2.1 Add a visible “多模态” entry and an Operate-mode detail form for enabled state, Base URL, model ID and transient API Key input.
- [x] 2.2 Add loading, saved-secret, saving, testing, validation, success and restart-required states with duplicate-action prevention and accessible live feedback.
- [x] 2.3 Extend the incumbent light/dark settings styles with readable fields, a compact visual route preview and responsive behavior, then update renderer/preload contract tests.

## 3. Local Core startup integration

- [x] 3.1 Make the trusted local Core launcher read only a complete enabled relay configuration and map it to the existing multimodal fallback environment without changing the DeepSeek text route.
- [x] 3.2 Add focused launcher/config contract coverage for enabled, disabled, incomplete and secret-missing states.

## 4. Verification and visible handoff

- [x] 4.1 Run desktop unit/preload/type/build checks, focused launcher validation, the Impeccable detector and strict OpenSpec validation.
- [x] 4.2 Fully restart the local Core, gateway and Electron stack, bring Meguri and its settings window to the foreground, and visually verify the new unconfigured relay entry without contacting an external provider.
