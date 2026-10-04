## Why

The Meguri-owned Vue desktop frontend replaced the legacy AIRI page without carrying over its Everything-backed `@` resource picker, so local-file references can no longer be searched, reviewed, or attached from the active chat composer. The three compact floating controls are also anchored to the lower-right corner, while the intended desktop-pet layout places them at the upper-right.

## What Changes

- Restore AIRI-compatible `@` resource resolution for leading and embedded references, including quoted/braced names and the `@@` escape.
- Search the existing loopback `/v1/resources/search` boundary, show safe metadata-only Everything candidates, and handle loading, empty, unavailable, and stale-response states.
- Allow one or more selected resources to be reviewed and removed before sending, then include the validated attachment records in the canonical turn request.
- Preserve the user's surrounding message when an embedded `@` reference is resolved and provide keyboard-accessible candidate selection.
- Re-anchor the three compact floating controls and their expandable drawer to the upper-right corner of the frameless stage.
- Restore the Meguri-specific AIRI fork shortcuts as true global shortcuts for chat, centering, and outfit switching.
- Restore AIRI-style fade-on-hover behavior for the pinned stage: the portrait fades and becomes click-through while chat, controls, panels, and resize edges stay interactive.
- Keep the current AIRI-derived warm light and stone dark visual system without adding unrelated interface regions.

## Capabilities

### New Capabilities

- `native-desktop-resource-interactions`: Defines the Everything-backed `@` resource-selection flow and the upper-right floating-control placement in the Meguri-owned desktop renderer.

### Modified Capabilities

None.

## Impact

- Changes `apps/desktop-meguri` chat components, conversation state/request construction, Electron shortcut registration, window preferences, and renderer styles.
- Reuses the existing authenticated desktop gateway and local Java Core resource endpoints; it does not execute `es.exe` from the renderer or weaken resource safety policy.
- Adds focused unit/contract coverage and a live local Everything smoke check.
- Does not change the legacy AIRI frontend, Java resource policy, or unrelated desktop settings.
