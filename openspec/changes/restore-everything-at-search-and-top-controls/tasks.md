## 1. Regression Baseline

- [x] 1.1 Add pure resource-adapter tests for valid/malformed candidates, resource resolution, and safe attachment construction.
- [x] 1.2 Add conversation request coverage proving selected Everything attachments and filename-only context are sent and retained for retry.
- [x] 1.3 Extend the AIRI parity contract to require `@` search affordances and an upper-right compact-controls anchor.

## 2. Everything Resource Flow

- [x] 2.1 Implement typed local-resource models and loopback resolver/search calls with bounded results, cancellation, and error normalization.
- [x] 2.2 Restore the debounced AIRI-compatible `@` picker in the native composer, including loading/empty/error states and stale-response protection.
- [x] 2.3 Add keyboard candidate navigation, explicit selection, duplicate prevention, removable metadata-only chips, and draft preservation.
- [x] 2.4 Include selected attachment records in canonical turn creation, visible filename context, successful-send cleanup, and retry state.

## 3. Desktop Layout

- [x] 3.1 Move the three compact floating controls and their drawer anchor to the upper-right without changing their supported actions.
- [x] 3.2 Style the picker and chips in the existing warm-light and stone-dark palettes with overflow and accessibility safeguards.

## 4. AIRI Desktop Interactions

- [x] 4.1 Add regression coverage for the AIRI fork shortcut bindings and wrapped outfit selection.
- [x] 4.2 Register global chat, center, and outfit shortcuts in the Electron main process without replacing the visibility shortcut.
- [x] 4.3 Add a persisted fade-on-hover window preference and expose it in the existing settings/control surfaces.
- [x] 4.4 Classify interactive/passive pointer regions, fade the hovered portrait, and apply recoverable Electron click-through while pinned.

## 5. Verification

- [x] 5.1 Run desktop unit/preload tests, typecheck, production build, and strict OpenSpec validation.
- [x] 5.2 Exercise the local Everything endpoint with the installed `es.exe`, including a real safe query and unavailable/empty behavior.
- [x] 5.3 Restart the native desktop app and visually verify shortcuts, click-through recovery, upper-right controls, and `@` search/selection in light and dark themes.
- [x] 5.4 Run the Impeccable detector over changed UI targets and resolve applicable findings.
