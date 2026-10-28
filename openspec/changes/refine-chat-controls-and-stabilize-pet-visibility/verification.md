## Verification

| Dimension | Result |
| --- | --- |
| Completeness | 9/9 implementation and validation tasks complete; all 3 requirements covered |
| Correctness | Screen-edge fitting, manual movement/resizing, Galgame returns, native hover recovery, and header/drawer interaction verified |
| Coherence | Existing conversation tokens and all companion actions preserved; fixed portrait sizing retained |

## Reproduction and fixes

- The original layout controller returned x=1120 instead of x=1480 after a right-edge panel cycle. Regression tests also reproduced drift on negative-coordinate/narrow displays and after manual movement.
- The isolated Electron renderer remained hover-hidden when native cursor coordinates moved outside the window without a DOM mouseleave. The fixed polling loop now resolves exterior positions and recovers after missing, invalid, or rejected samples.
- Closing chat briefly selected an expanded native surface before the child panel event settled. Post-render surface fitting removes this intermediate transition while preserving normalization after deliberate manual resizing.
- Fractional-DPI compact-size rounding caused a further 1px position drift per panel cycle. Restoring saved compact coordinates with canonical compact dimensions prevents accumulation.
- Follow-up conflict check reproduced two active controls rails during the 220ms chat exit animation when hiding expanded chat with its drawer open. The outgoing chat is now inert, excluded from accessibility, pointer-disabled, and explicitly marked non-draggable; only the floating rail remains interactive. Re-entry restores normal interaction.
- The same follow-up reproduced Escape failing to close controls opened through the stage context menu after a layout remount. Opening controls now focuses the active toggle, keeping Escape within the controls handler.

## Requirement evidence

- Unified header: ChatDock.vue header slot, ControlsIsland.vue inline rail and keyboard handling, AmbientStage.vue conditional placement, and styles.css. `chat-interaction-smoke.mjs` verifies one active rail, named/hittable 30px buttons, full-width history, viewport-fitting drawer, retained keyboard focus, Escape dismissal, and a single row at 360px. Light/dark screenshots inspected.
- Reversible placement: stage-layout.mjs compact return snapshot plus stage-layout.test.ts cover repeated right/bottom-edge, negative-coordinate, narrow-display and Galgame cycles, manual movement/resizing, and native rounding. The Electron check repeats six native workspace cycles at x=1480 without position drift.
- Hover recovery: StageView.vue polling and stage-pointer.ts exterior handling are covered by pointer unit tests and the Electron check for absent DOM leave, missing/NaN cursor samples, polling rejection and resumption, and returning to interactive controls. Existing Galgame pointer behavior tests pass.

## Validation results

- `pnpm --filter @meguri/desktop-meguri test`: 63 files / 426 tests pass; preload, instance-owner and tray Electron smoke checks pass.
- `pnpm --filter @meguri/desktop-meguri typecheck`: pass.
- `pnpm --filter @meguri/desktop-meguri build`: pass; existing bundle-size advisory remains.
- `pnpm --filter @meguri/desktop-meguri test:chat-interaction`: pass; no renderer errors.
- `pnpm --filter @meguri/desktop-meguri test:visual-bubbles`: pass, including draft preservation, eight chat toggles, todo/reminder/attachment surfaces, manual resizing, voice replay, Galgame, and the local Live2D fixture; no renderer errors.
- `openspec validate refine-chat-controls-and-stabilize-pet-visibility --strict`: pass.
- Source whitespace checks: pass. No temporary debug instrumentation remains.
- Design detector ran once. Its four warnings refer to existing message blockquote/error and selected-item borders outside this change; no new header/control finding.

Artifacts: `.codex-runtime/chat-interaction-smoke/` and `.codex-runtime/bubble-layout-smoke/`.

## Floating-controls follow-up validation

- The Electron check measures computed `-webkit-app-region` values: the collapsed floating move handle and expanded chat header are draggable; header actions are `no-drag`; outgoing headers become `no-drag` during exit. This checks the native-region configuration, not a physical OS mouse-drag gesture.
- Hiding expanded chat with its drawer open now reports exactly one active floating rail, with the outgoing chat inert and its drag region disabled.
- Four rapid hide/show cycles during the animation preserve one active rail and restore a non-inert expanded chat. Context-menu controls open and close with Escape in both expanded and compact layouts.
- Six right-edge native workspace cycles retain their initial position. Pointer recovery, five header hit targets, keyboard drawer use, and the narrow single-row header continue to pass.
- Re-ran all 426 unit tests, typecheck, production build, both Electron interaction/layout checks, strict OpenSpec validation and whitespace checks successfully. Both renderer checks report no console errors.

## Assessment

No outstanding correctness or completeness issues. The desktop must be fully restarted to load the updated Electron main-process layout controller. The OpenSpec change remains unarchived.
