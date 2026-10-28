## Context

See proposal.md and the supplied screenshots. Conversation tokens currently cover messages but not the legacy file tags, composer, or floating controls. The prior fix gives ambient portraits a 350×540 CSS-pixel surface; native windows still reuse a single stageBounds record and controls remain at the window's top/right edge.

## Goals / Non-Goals

**Goals:** Coherent readable fresh colors, compact native fitting, stable character placement, and independent expanded/Galgame dimensions.

**Non-Goals:** Changing character assets or scale, replacing the settings app, arbitrary renderer-controlled native bounds, backend changes, or automated test additions.

## Decisions

1. Use the existing palette function as semantic authority for all companion chat and control roles. Add explicit opaque input/file/action roles and map legacy selectors to them. Set light/mint defaults, and apply only those two appearance fields to this user's local state as requested; retain their other preferences.
2. Share the fixed portrait geometry between CSS setup and host fitting. The compact frame is 390×560 DIP around the 350×540 portrait. Expanded chat restores saved dimensions, initially 440×720; Galgame uses its own saved dimensions, initially 860×720.
3. On ambient transitions preserve the portrait's screen center/top: derive the old portrait top from chat layout, choose the new size, then compensate x/y using the new portrait top. Clamp to the matching display's work area afterward. Electron bounds and renderer CSS both use logical pixels at the existing zoom, so never multiply by scaleFactor.
4. Store expanded dimensions and Galgame dimensions separately from current stageBounds. Preserve an ambient return snapshot while Galgame is open. Persist the current mode with bounds so a restart cannot interpret Galgame as a compact portrait position.
5. Add only a stage-authenticated named surface switch; preferences continue to drive chat fitting in the host. Anchor the floating controls to portrait geometry so manual window resizing cannot leave them far from the character.
6. Give the compact dock 200px of height and an 18px bottom inset. A 52px composer accommodates the enlarged two-line placeholder, with enough room for the empty-state text above it.

## Risks / Trade-offs

- [Display edges prevent exact position preservation] → Keep the window reachable through work-area clamping.
- [Concurrent delayed bounds saves] → Save mode and matching layout dimensions together from the current window at timer execution; capture old expanded size before fitting.
- [Old main process lacks the new IPC] → Require desktop restart for host fitting; provide a graceful renderer failure path.
- [Compact chat can cover the lower portrait] → Preserve character scale and foreground chat usability.
- [Wallpaper or deliberately translucent bubbles alter perceived contrast] → Keep file/input surfaces opaque and report color checks against the actual theme surfaces.

## Migration Plan

Implement colors and named window fitting, rebuild the renderer, inspect representative UI states, validate specifications, and save a concise report. Preserve a local state backup before applying the requested appearance choice. Rollback restores the changed source and the saved state backup; no service migration is required.
