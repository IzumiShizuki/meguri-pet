## Context

See `proposal.md` for motivation and `specs/airi-desktop-ui-parity/spec.md` for observable behavior. The current native app owns one opaque startup window and one transparent stage window. The AIRI fork provides the visual reference: a transparent stage, bottom-right collapsible controls island, and an embedded chat dock at 12px insets with warm cream/amber light colors and stone/amber dark colors. AIRI settings run in a reusable 600x800 window, but most AIRI settings are not meaningful for Meguri's canonical Core/gateway architecture.

## Goals / Non-Goals

**Goals:**

- Make the native stage immediately recognizable as the current AIRI fork experience.
- Keep the main stage visually frameless while restoring compact and expanded floating controls.
- Add a real reusable settings window and live preference propagation.
- Keep Electron privilege and filesystem effects behind the preload boundary.
- Preserve the verified Windows transparency composition fix.

**Non-Goals:**

- Import AIRI Vue components, UnoCSS, stores, providers, plugins, updater, Live2D, VRM, or Spine runtime.
- Clone AIRI settings that have no native Meguri effect.
- Implement window snapping or target-window attachment in this slice.
- Replace the dedicated Meguri startup view.

## Decisions

### 1. Treat the AIRI fork as a behavioral and visual reference, not a dependency

Recreate the measured composition in Meguri-owned Vue and CSS. This keeps the native app small and prevents AIRI state or build tooling from becoming a new dependency. Importing the original controls was rejected because it pulls Electron Eventa, AIRI stores, authentication, providers, and UnoCSS utilities into the native app.

### 2. Replace top chrome with a bottom-right expandable controls island

The stage removes the persistent status pill and horizontal control row. A compact vertical island sits at bottom-right and exposes move, speech, and expansion controls; an adjacent drawer exposes supported actions and outfit selection. Runtime status appears inside the expanded drawer or a short-lived notice. This matches AIRI's hierarchy and keeps the stage free of title-bar-like chrome.

### 3. Use an independent reusable settings BrowserWindow

Electron main owns one settings window with a normal frameless application surface, 600x800 default size, display-safe placement, and hide-on-close behavior. The preload exposes only typed preference reads/writes and specific window actions. A settings route renders categories inside the existing Vue bundle.

Alternative considered: a modal over the stage. Rejected because AIRI uses a separate settings workspace and a modal would distort stage hit testing and transparent composition.

### 4. Persist one versioned preference document in the Electron host

Appearance, chat, character, speech, shortcut, and window preferences live in the existing desktop state file. Renderer localStorage remains readable for one migration pass, then host state becomes authoritative. The host broadcasts preference changes to all Meguri windows.

Alternative considered: continue independent localStorage state. Rejected because the stage and settings windows have separate renderer lifecycles and need consistent live updates.

### 5. Match AIRI's measured chat geometry and palette explicitly

Use exact AIRI fork anchors: 12px side/bottom insets, 11.5rem compact height, and 55vh expanded height. Light surfaces use amber-50/orange-200-family tones; dark surfaces use stone-900 with restrained amber borders. The composer and history inherit the same material rather than the prior dusk-purple tokens.

### 6. Keep stage transparency and Windows composition constraints separate

The BrowserWindow remains `transparent: true` and MUST NOT set an explicit fully transparent `backgroundColor`, because that configuration was verified to make the complete Electron 43 window invisible under Windows DWM. CSS may still use transparent page regions.

## Risks / Trade-offs

- [Risk] Pixel-perfect parity can drift as the AIRI fork changes. → Keep measured geometry and interaction expectations in tests and OpenSpec; treat future AIRI changes as explicit migration slices.
- [Risk] Separate renderers can overwrite preferences. → Serialize updates through Electron main and broadcast the normalized result.
- [Risk] Controls near the chat dock can overlap at narrow heights. → Reserve the controls footprint and add compact-height responsive rules.
- [Risk] A reduced native settings catalog may feel smaller than AIRI. → Include only functional settings and maintain a documented parity backlog rather than inert options.
- [Risk] Transparent click-through can block expanded controls. → Treat the controls drawer, chat, settings trigger, and resize edges as explicit interactive hit-test regions.

## Migration Plan

1. Add host preference and settings-window contracts with tests.
2. Introduce the settings route and supported preference controls.
3. Replace top chrome with the AIRI-style controls island.
4. Restyle and reposition the embedded chat dock using measured AIRI values.
5. Migrate existing local presentation preferences into host state.
6. Validate unit, preload, type, build, browser interaction, and real Windows desktop appearance.

Rollback is restoring the previous native stage components; the canonical conversation and gateway paths are unchanged, and the AIRI fallback launcher remains available.
