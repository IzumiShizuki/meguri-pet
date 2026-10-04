## 1. Host and preference foundation

- [x] 1.1 Define normalized versioned desktop preferences for appearance, chat, character, window, speech, and shortcuts with migration and unit tests.
- [x] 1.2 Extend the narrow preload contract for preference read/update subscriptions and explicit settings, centering, refresh, always-on-top, and quit actions.
- [x] 1.3 Add a reusable 600x800 settings BrowserWindow with display-safe placement, hide-on-close behavior, and focused Electron host tests.

## 2. AIRI-familiar stage controls

- [x] 2.1 Replace the persistent top status/control chrome with a bottom-right compact vertical controls island and expandable drawer matching the AIRI interaction hierarchy.
- [x] 2.2 Implement working move, speech, settings, chat, outfit, theme, always-on-top, center, refresh, hide, and quit actions with accessible labels and states.
- [x] 2.3 Keep transient readiness/error feedback available without adding a persistent title bar and update click-through hit testing for all expanded controls.

## 3. Settings experience

- [x] 3.1 Build the Meguri settings shell, navigation, frameless window controls, and appearance/service overview sections.
- [x] 3.2 Add functional chat, character, window, speech/TTS, and shortcut settings bound to shared host preferences.
- [x] 3.3 Ensure unsupported AIRI-only settings are absent and document the remaining parity backlog.

## 4. Chat and visual parity

- [x] 4.1 Match AIRI's embedded chat insets, compact and expanded heights, warm cream/amber light palette, stone/amber dark palette, typography, composer, and message states.
- [x] 4.2 Adjust character-stage and artifact placement for compact/expanded chat and controls across supported window heights.
- [x] 4.3 Remove obsolete purple stage tokens and title-bar-like visual treatments without changing the startup view's established Meguri identity.

## 5. Verification

- [x] 5.1 Add unit and renderer interaction coverage for preferences, controls expansion, settings actions, themes, and chat geometry.
- [x] 5.2 Run preload smoke, unit tests, typecheck, production build, Impeccable detector, and OpenSpec strict validation.
- [x] 5.3 Inspect the native stage and settings in real Windows windows against AIRI source measurements and correct material parity defects.
