## Context

The Electron controller clamps expanded windows to the current display work area. It currently computes collapse from these already-clamped bounds. The Vue stage polls native cursor coordinates because click-through windows do not reliably receive DOM mouseleave events, but discards samples outside the viewport. The separate vertical controls rail overlaps expanded chat and requires 74px of padding through the history.

## Goals / Non-Goals

**Goals:** Preserve compact placement independently from automatic panel fitting; resolve each completed pointer poll; make the existing compact chat header the single home for upper-right actions.

**Non-Goals:** New themes, backend changes, new window modes, and changes to the fixed portrait size.

## Decisions

1. Record compact return bounds and the actual fitted expanded bounds in the layout controller. On collapse, apply the difference between current and fitted portrait anchors to the saved compact bounds, then clamp to the current work area. This retains deliberate movement/resizing without making automatic fitting permanent. Preserve this snapshot through Galgame transitions. Merely undoing width changes fails near screen boundaries.
2. Send out-of-window native samples through the pointer resolver, explicitly treating them as a visible, click-through exterior. Missing/failed polling restores normal interaction and does not stop the next poll. Do not depend on DOM leave delivery while the native window ignores input.
3. Reuse ControlsIsland through an expanded-header slot, with an inline rail for speech and more controls; the draggable header replaces its redundant drag button. Keep the floating rail for compact mode and all existing drawer actions. Use the incumbent conversation tokens, consistent SVG strokes, 30px header buttons, visible focus, and a right-aligned drawer. Keep focus within the drawer from triggering timed collapse.
4. Use existing Vitest layout seams and an isolated Electron renderer harness with injected native cursor positions. The harness reproduces missing DOM leave, checks recovery, inspects bounds and hit targets, and captures screenshots without touching live conversation state.
5. Defer native-surface fitting until Vue finishes the component update. Collapsing history updates preferences before the child emits its collapsed state; fitting the intermediate state briefly opens and closes a native panel and introduces fractional-DPI drift even when chat itself should stay compact.
6. Make outgoing chat inert and hidden from accessibility at the beginning of its existing exit animation. Disable pointer input and native draggable regions during that animation. The floating controls are already mounted when the inline controls begin leaving; preserving the old input regions would temporarily create two active toolbars. Restore interaction on re-entry or cancellation. Opening controls from the stage context menu focuses the current toggle so Escape is delivered to the active controls.

## Risks / Trade-offs

- Fractional-DPI native rounding → capture the actual fitted bounds and allow only the existing native rounding tolerance in integration checks.
- Manual movement followed by display changes → compute portrait-anchor displacement and clamp the result to the matching current display.
- Controls are re-mounted between compact and expanded presentation → all durable settings and actions stay in existing stores; transient drawer state can reset when changing layout.
