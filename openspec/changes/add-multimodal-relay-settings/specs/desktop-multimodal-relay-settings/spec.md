## Purpose

让 Meguri 用户能够在现有桌面设置窗口中安全配置并验证一个 OpenAI-compatible 视觉中转站，同时保持密钥隔离、显式启用和重启后生效的运行边界。

## ADDED Requirements

### Requirement: Multimodal relay has a visible settings entry
The desktop SHALL expose a dedicated multimodal settings section that reports whether the relay is enabled, which model is selected, and whether a secret has already been stored without revealing that secret.

#### Scenario: User opens settings without a saved relay
- **WHEN** no multimodal relay configuration exists and the user opens the settings window
- **THEN** the settings home SHALL show the multimodal entry as unconfigured and the detail view SHALL present empty URL/model fields and an unconfigured secret state

#### Scenario: User opens an existing relay configuration
- **WHEN** a relay configuration and secret already exist
- **THEN** the detail view SHALL show the enabled state, Base URL and model while the API Key control SHALL indicate that a secret is saved without returning its plaintext

### Requirement: Relay configuration is validated and stored at a trusted boundary
The desktop SHALL validate and persist relay configuration in the trusted desktop process, SHALL keep the API Key outside repository and renderer preference storage, and MUST NOT return or log the plaintext secret after saving.

#### Scenario: User saves a complete remote relay
- **WHEN** the user provides an HTTPS Base URL, a valid model ID, an API Key and enables the relay
- **THEN** the desktop SHALL store the non-secret configuration and repository-external secret separately and SHALL return a sanitized configured state

#### Scenario: User keeps the existing secret
- **WHEN** a saved secret exists and the user saves URL, model or enabled-state changes with the API Key field empty
- **THEN** the desktop SHALL retain the existing secret and SHALL NOT require it to be re-entered

#### Scenario: User provides unsafe or incomplete settings
- **WHEN** a remote Base URL is not HTTPS, a model ID is invalid, or the relay is enabled without an available API Key
- **THEN** the desktop SHALL reject the save with a specific recoverable validation message and SHALL preserve the submitted form values for correction

### Requirement: Vision capability can be tested explicitly
The desktop SHALL contact a configured relay only after the user explicitly requests a connection test and SHALL verify image-input compatibility rather than only checking a generic models endpoint.

#### Scenario: Relay accepts an image request
- **WHEN** the user requests a test with valid settings and the relay accepts a bounded standard `image_url` request
- **THEN** the UI SHALL report that the model accepted visual input

#### Scenario: Relay rejects image input or cannot be reached
- **WHEN** the test times out, authentication fails, or the provider rejects the image request
- **THEN** the UI SHALL show a concise actionable failure and SHALL keep the form available for retry

#### Scenario: User only opens or edits the form
- **WHEN** the user opens the section or edits fields without pressing the test action
- **THEN** the desktop SHALL NOT contact the configured third-party endpoint

### Requirement: Local startup applies only enabled relay settings
The trusted local Core launcher SHALL translate a complete enabled relay configuration into the existing multimodal fallback settings while preserving the configured DeepSeek text route for turns without image or PDF content.

#### Scenario: Meguri starts with an enabled complete relay
- **WHEN** the local stack starts after a valid relay configuration was saved and enabled
- **THEN** image/PDF turns SHALL use the configured fallback Base URL, model and secret file while ordinary text turns SHALL retain the primary text model

#### Scenario: Relay is disabled or incomplete
- **WHEN** the saved relay is disabled, missing or incomplete at startup
- **THEN** the launcher SHALL leave the multimodal fallback disabled and SHALL NOT prevent the existing text-only Core from starting

#### Scenario: User saves settings while Meguri is running
- **WHEN** the user successfully saves relay settings in the current desktop session
- **THEN** the UI SHALL state that the new route applies after Meguri is restarted and SHALL NOT silently restart an active conversation
