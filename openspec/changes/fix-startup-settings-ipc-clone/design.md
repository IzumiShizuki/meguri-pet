## Context

SettingsView uses a deep-reactive ref for startupDraft. Its save handler forwards startupDraft.value through contextBridge to preload, which forwards its argument to ipcRenderer.invoke. Two paragraphs render startupStatus and startupFeedback, including identical messages. Main already validates the two boolean fields and separates persistence from task application.

## Goals / Non-Goals

**Goals:** Preserve selected false values, send a plain DTO before crossing contextBridge, retain existing save/pending behavior, and deduplicate identical displayed messages.

**Non-Goals:** Change task privileges, Windows configuration, deployment, or unrelated settings bridges.

## Decisions

- Construct the two-field object in the renderer handler. Sanitizing only in preload would occur after contextBridge has already received the reactive object. JSON roundtripping or broad cloning would copy unintended fields and obscure the explicit contract.
- Hide the feedback paragraph only when its message equals the status message. Distinct progress or error information must remain visible.
- Execute the production handler extracted from its TypeScript AST with real Vue refs and the production preload in a VM whose invoke performs structuredClone. Compile the actual feedback template fragment for rendering assertions. Add an isolated Electron smoke to cover the real contextBridge and IPC boundary with stub handlers, never the live Windows configurator.

## Risks / Trade-offs

- Isolated tests do not prove Task Scheduler accepts changes → clearly report this limit; retain existing persistence/application workflow tests.
- Source-driven test harness could drift → extract named AST declarations and actual feedback markup, failing explicitly if absent.
