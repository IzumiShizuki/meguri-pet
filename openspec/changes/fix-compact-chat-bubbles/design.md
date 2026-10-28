## Context

See proposal.md for motivation. The ambient portrait already uses a fixed 350 x 540 CSS-pixel surface. Chat still uses a 200px collapsed history grid and a 55vh expanded overlay. Optional reminder/error rows are implicit grid rows, compressing history. The native controller has compact/expanded/Galgame modes but persists legacy tall expanded bounds. MeaPet's public README describes reply bubbles whose full content opens on click: https://github.com/suan-11/mea-pet-public#回复气泡与-tts-时序.

## Goals / Non-Goals

**Goals:** Share portrait geometry across the host and renderer; retain a mounted chat component across size changes; make temporary tool visibility determine native fitting without altering the saved history preference.

**Non-Goals:** Copying MeaPet code, redesigning Galgame, changing services, or introducing character scale settings.

## Decisions

1. Compact chat uses a latest-reply speech bubble with a tail, above a small composer bubble. Reuse the existing mint palette (#f1f7f5 surface, #ffffff reply, #206856 accent, #243b33 text, #769185 border) and existing YaHei/Segoe typography. Full history remains available on click. A transparent wrapper avoids a large empty panel. Reusing the compressed full-history grid would preserve the reported symptom.
2. Expanded ambient windows default to 800 x 560 DIP. A 390px portrait lane and adjacent bubble show the pet and functional panel together. The portrait retains its 350 x 540 dimensions. Narrow windows fall back to a foreground panel within the viewport rather than scaling the character. Native fitting anchors the portrait center/top, not the full window center.
3. Chat derives expansion from saved history preference or active Todo/reminder/resources/errors. It emits its temporary panel state to StageView. Skills/work use the same ambient panel layout. Extend the existing validated presentation-surface request with `ambient-panel`; do not create an arbitrary resize API. Todo closes back to compact naturally; explicit history expansion persists.
4. Chat uses flex with a bounded scrollable body containing reminder/history/notices, and a fixed composer footer. Todo fills the body instead of overlapping the composer. Maintain the component/draft/resources during layout changes. Hide only expanded tools in compact mode.
5. Persist a stage geometry version alongside bounds. Old expanded sizes migrate once to the new defaults; new manually resized expanded dimensions remain remembered. Compact size stays fixed, Galgame keeps its existing return handling.
6. Compare Live2D renderer/model preference fields individually. Updating chat preferences must keep the existing canvas and model rather than reload them. Allow up to 2 DIP native rounding for an already-compact window to avoid redundant fitting on fractional-DPI Windows displays. Observe the renderer viewport as well as native resize events.

## Risks / Trade-offs

- [Panel cannot fit beside the pet on a narrow display] → Keep the portrait fixed and render the large bubble in front within viewport limits.
- [Display-edge clamping moves the pet] → Preserve its anchor whenever possible, otherwise clamp to the display work area.
- [Temporary panel closes while another is open] → Derive native mode from all active panels and saved history visibility.
- [Production process still uses older main code] → Rebuild assets, exercise an isolated Electron window/controller, and require a desktop restart for the updated host.

## Migration Plan

Build the renderer and restart the desktop host to load it. Existing preferences and user data remain. Geometry version migrates old sizes on first startup. Rollback restores the changed source/build; no service/data migration is needed.
