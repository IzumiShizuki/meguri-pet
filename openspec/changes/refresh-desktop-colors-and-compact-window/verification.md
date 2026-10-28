# Verification: refresh-desktop-colors-and-compact-window

Date: 2026-10-05. Changes were implemented through one palette sub-agent and root host integration, with a separate read-only layout assessment.

## Summary

| Dimension | Result |
|---|---|
| Completeness | 6/6 tasks complete; 3 requirements and 9 scenarios mapped |
| Correctness | Production build, typecheck, JavaScript syntax checks, and strict OpenSpec validation pass; representative browser layouts and light/dark tokens inspected |
| Coherence | Existing palette preferences, fixed character scale, named IPC authority, and host-owned bounds persistence retained |

## Requirement and scenario evidence

### Readable companion colors

`conversation-palette.ts` defines explicit opaque input/file surfaces, readable foregrounds, placeholder, action text, border, and semantic status colors, using the existing contrastRatio helper. `styles.css` replaces legacy orange/brown companion rules with these roles. Chat/file text is 12px and relevant supporting text is 11px. Host, renderer fallback and preview defaults agree on light/mint. The actual user's local appearance and palette fields were changed to light/mint after a same-directory state backup.

Manual browser inspection at 390×560 and 473×789 confirmed the light compact and expanded layouts, then the dark appearance/control drawer. Current computed mint pairs:

| Foreground/background role | Light | Dark |
|---|---:|---:|
| Primary text/surface | 11.07:1 | 12.62:1 |
| Secondary text/surface | 5.58:1 | 8.51:1 |
| File text/file surface | 5.42:1 | 5.80:1 |
| Placeholder/input | 5.59:1 | 7.37:1 |
| Action text/accent | 6.61:1 | 8.05:1 |

File contrast was calculated from the actual theme roles; the browser's empty preview did not contain real file-history rows. Other presets have source-level readable-color handling; they were not individually exercised.

### Native frame fits compact companion

`shared/stage-geometry.mjs` owns 350×540 portrait geometry and 390×560 compact dimensions. `main/stage-layout.mjs` fits startup and preference changes, restores independent expanded dimensions, and preserves character center/top with work-area clamping. It detects either width or height exceeding compact bounds when migrating legacy dimensions. The layout reviewer caught the original height-only legacy check; that finding was fixed before delivery.

Both hide and collapse reach compact mode. Reapplying compact mode also fits a manually enlarged compact frame. Host preference changes cover menu, tray, and automatic chat expansion paths. CSS controls share the portrait's top anchor and remain near its right edge. The browser's compact snapshot measured character x≈20, y=10, width=350, height=540 and controls y=10, with no horizontal document overflow. The composer measured 52px height. Compact dock height was increased to 200px after inspection found the larger empty-state text needed additional room.

### Presentation and display boundaries

`stage-layout.mjs` separately retains expanded/Galgame dimensions and an ambient return snapshot. Delayed move/resize saves inspect the current mode; close saves and clears its pending timer. `index.mjs` uses Electron matching-display workArea for startup and transitions, preserves negative coordinates, and never multiplies display scale factors. The named surface IPC is only exposed to stage preload and checks trustedStageRenderer plus the exact ambient/galgame values. StageView invokes it before entering/exiting Galgame and resets cached local pointer positions after host resizing.

These native lifecycle/security scenarios were reviewed in source, not exercised in the live Electron main process. The browser preview's surface bridge is intentionally a no-op for native sizing.

## Commands and findings

- `pnpm --filter @meguri/desktop-meguri typecheck`: passed.
- `pnpm --filter @meguri/desktop-meguri build`: passed; rebuilt after the final dock-height adjustment. Existing >500 KB chunk warnings remain; no performance claim.
- Node `--check` for main, preload and stage-layout: passed.
- `openspec validate refresh-desktop-colors-and-compact-window --type change --strict --no-interactive`: passed.
- `git diff --check` on changed tracked desktop files: passed; only Git's line-ending notices remain.
- Impeccable mechanical scan: four existing side-border style findings in Galgame message/error rules, recorded in `tmp/fresh-desktop-design-scan.json`. Those incumbent structural markers were retained; this request changes colors and ambient frame fitting. They are not contrast failures.
- No automated tests were added or run. Native live mode transitions, multi-monitor/DPI behavior, actual user file-history rows, and Live2D runtime transitions remain unperformed checks.

No missing implementation or design divergence found. Keep the change open. Restart the desktop app to load its updated main/preload window fitting; a renderer-only refresh cannot load those host changes. Compact chat can still overlay the lower portrait while its scale remains fixed.
