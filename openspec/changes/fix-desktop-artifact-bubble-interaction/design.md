## Context

The desktop page currently renders each artifact notification as a `div` with `role="button"` and attaches one click listener to the wrapper, while a nested close button is hidden until hover. The wrapper and its close control therefore share an implicit hit target, and the open path is coupled to the wrapper's asynchronous handler. See `proposal.md` and the capability delta for the user-visible contract.

## Goals / Non-Goals

**Goals:**

- Give opening and dismissal separate native button controls with unambiguous hit targets.
- Preserve the current local Electron IPC, browser `window.open`, icon lookup, artifact feed, and max-three-bubble behavior.
- Keep the implementation testable without booting the full Core runtime.
- Make keyboard and pointer behavior identical and keep failed opens dismissible.

**Non-Goals:**

- Changing artifact discovery, filesystem allow-lists, or Electron security policy.
- Changing bubble placement, animation, or the artifact feed protocol beyond the interaction markup needed for hit testing.

## Decisions

1. **Use a wrapper with two sibling native buttons.** The bubble container remains a visual/layout wrapper; a dedicated `.artifact-open` button handles opening, and `.bubble-close` handles dismissal. This avoids invalid nested interactive elements and is more reliable than relying on `role="button"` plus event bubbling.

2. **Keep removal conditional for opening.** The open action removes the bubble only after `openArtifactItem` returns success. A rejected or failed open leaves the bubble in place so the user can retry or dismiss it.

3. **Keep icon replacement scoped to the visual face.** The asynchronous native icon lookup replaces only the icon/badge inside the open button and checks connection state before mutation; it does not rebuild the buttons or rebind listeners.

4. **Extract the interaction seam for tests.** Put the small DOM construction/activation logic in a browser-side module that accepts the document, artifact opener, and container dependencies. The page supplies its existing functions, while Node tests can use a minimal DOM harness to assert exact click, keyboard, removal, and failure behavior.

## Risks / Trade-offs

- [Risk] The close control changes from hover-only to always available and may slightly increase visual density. → Mitigation: keep it compact and positioned over the bubble edge while preserving the existing hover emphasis.
- [Risk] A slow native open operation can leave a bubble visible longer than before. → Mitigation: allow dismissal immediately and remove only on confirmed success, preventing silent loss of an artifact notification.
- [Risk] Browser test doubles may not model Electron IPC exactly. → Mitigation: test the renderer contract at the opener boundary and retain the existing Electron allow-list tests unchanged.

## Migration Plan

No data migration is required. Deploy the renderer changes with the existing desktop gateway; existing localStorage cursors and seen-artifact state remain compatible. Rollback is a renderer-only revert.
