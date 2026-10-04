## Context

See `proposal.md` for motivation and `specs/native-desktop-resource-interactions/spec.md` for the behavior contract. The legacy AIRI renderer already defines the expected resource flow, while the active Vue renderer currently sends text-only canonical turns. The desktop gateway already authenticates renderer requests and routes `/v1/resources/*` to the local Java Core, whose Everything adapter applies path and metadata safety policy.

## Goals / Non-Goals

**Goals:**

- Reuse the existing Core resolver and Everything safety boundary instead of duplicating trusted path logic in Electron.
- Keep resource-picker state explicit, reversible, and isolated from persisted conversation history until send.
- Preserve canonical turn creation, streaming, retry, and the incumbent AIRI-derived visual language.
- Make fast-changing searches race-safe and fully operable from the keyboard.
- Preserve the AIRI fork's global shortcut behavior even while another application has focus.
- Let a pinned desktop pet stop occluding other applications without making Meguri's controls unusable.

**Non-Goals:**

- Directly launch `es.exe` from the renderer or expose arbitrary filesystem IPC.
- Read file content during search or selection.
- Add drag/drop, clipboard file attachment, a general attachment browser, or new AIRI-only settings in this change.
- Change the Java Everything index, allowed-path policy, or gateway authentication model.

## Decisions

### Reuse both canonical local-resource endpoints

The renderer will POST the current draft to `/v1/input/resolve` after a short debounce and search `/v1/resources/search` only when the response kind is `resource`. This preserves Java's exact handling of leading, embedded, quoted, braced, bare, and escaped markers. Reimplementing the parser in Vue was rejected because it would create a second syntax authority and drift from AIRI.

### Keep picker state in the chat surface and attachment delivery in the conversation store

The chat component will own transient candidates, hint text, keyboard highlight, selected chips, cancellation, and stale-response sequencing. The conversation store will accept validated attachment records, include them in `createTurn`, retain them for retry, and render a metadata-only filename note in history. Moving candidates into global state was rejected because they are composer-local and must disappear with the active draft.

### Treat Core candidate records as untrusted at the renderer boundary

A small pure adapter will require non-empty `id`, `name`, and `path` strings and will normalize optional metadata before a candidate becomes an attachment. The authoritative security check remains in Java, including send-time validation; renderer validation only prevents malformed UI/API data from entering state.

### Debounce and cancel resource discovery

Input resolution will be debounced to match AIRI's responsive behavior. Each new draft invalidates the prior sequence and aborts outstanding resolver/search requests. A sequence guard remains in addition to cancellation because an already-completed fetch can still schedule an older render update.

### Re-anchor without redesigning the controls

The existing controls island grid remains unchanged except for its vertical anchor/alignment and viewport constraint. The drawer stays in the same grid cell with an inline offset to the left, so expanding it does not shift the three-button rail.

### Register the fork's Meguri shortcuts in Electron

The main process will own `Ctrl+Alt+H` for chat, `Ctrl+Alt+C` and `Ctrl+Alt+Shift+M` for centering, and `Ctrl+Alt+[` / `Ctrl+Alt+]` for outfit switching. Renderer-only key handlers were rejected because they stop working as soon as another desktop application has focus. The existing configurable visibility shortcut remains independent.

### Use forwarded mouse movement to recover from click-through

When always-on-top and fade-on-hover are both enabled, the renderer classifies the pointer against resize borders and explicit interactive surfaces. Passive stage/background regions become click-through through Electron's `setIgnoreMouseEvents(true, { forward: true })`; forwarded mouse movement lets the renderer restore normal input when the cursor reaches chat, controls, skills, artifacts, or a resize border. The portrait fades only while the pointer is inside its rendered bounds, matching the AIRI fork's visible feedback without hiding interactive UI.

## Risks / Trade-offs

- [The input resolver is temporarily unavailable] → Preserve draft and selected chips, show a scoped retryable message, and allow escaped/ordinary text to continue once the marker is removed.
- [A selected file changes before send] → Keep Java's send-time validation authoritative; never infer safety from the displayed candidate alone.
- [Long Windows paths overwhelm the compact dock] → Truncate candidate paths visually while retaining full accessible titles, and allow the result list to scroll within a bounded height.
- [Search responses arrive out of order] → Combine `AbortController` cancellation with monotonic request sequencing.
- [A turn fails after submission] → Retain the last attachment set in conversation retry state so a retry preserves user intent without silently widening access.

## Migration Plan

1. Add regression tests for resolver/search wiring, attachment request delivery, and upper-right placement.
2. Add the resource adapter/service and picker UI, then update conversation turn construction.
3. Move the existing controls anchor and verify compact/expanded states in light and dark themes.
4. Run unit, preload, type, build, live Everything, and visual smoke checks; restart the desktop app with the rebuilt renderer.
5. Roll back by reverting only the Vue resource-picker/store additions and the controls anchor declarations; Core and gateway remain unchanged.
