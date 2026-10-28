# Verification: fix-ambient-character-resize

Date: 2026-10-05.

## Summary

| Dimension | Result |
|---|---|
| Completeness | 3/3 tasks implemented; 2 requirements and 4 scenarios mapped |
| Correctness | Source inspection and production build pass; live resizing was not exercised |
| Coherence | Shared ambient CSS owns PNG/Live2D dimensions, with existing responsive chat and foreground layering preserved |

## Requirement and scenario mapping

- Stable scale during window resizing: `styles.css:90` defines a 350 by 540 CSS-pixel surface; `styles.css:92` uses those fixed dimensions. Viewport size affects position only.
- Chat layout changes: `styles.css:94` changes the bottom clearance, not dimensions. The previous short-window bottom override has been removed. Chat keeps its existing responsive width and expanded 55vh height.
- Smallest supported window: native minima are 360 by 560; the fixed surface fits with horizontal margins and 8px top clearance. `max(8px, ...)` bounds its top. Existing chat z-index 30 keeps input controls in front when its lower region overlaps the portrait.
- Renderer/image changes: AmbientStage applies the same character-sprite class to PNG and Live2D. PNG uses object-fit: contain. Live2D's container fills this fixed surface and its fallback also uses contain; the duplicate scoped sizing override is removed.

## Evidence

- `pnpm --filter @meguri/desktop-meguri build`: passed; production renderer rebuilt. Existing large-chunk warnings remain.
- `openspec validate fix-ambient-character-resize --type change --strict --no-interactive`: passed.
- `git diff --check` for the two changed renderer source files: passed after removing an end-of-file blank line.
- No new or existing automated tests were run for this request. No live desktop resize, actual character screenshot, or Live2D runtime check is claimed by this source inspection.

## Findings and delivery

No missing implementation or design divergence found. Runtime verification remains unperformed: the four scenarios are mapped to code rather than executed in the user desktop session.

Refresh the stage or restart the desktop app to load rebuilt assets. In a compact window, expanded chat can cover the lower portrait while the character stays at its fixed scale. Service configuration and existing unrelated work are preserved. This change is not archived.
