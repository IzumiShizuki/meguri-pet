## 1. Reproduce

- [x] 1.1 Add and run failing screen-edge layout regression tests and an isolated Electron hover-recovery harness.

## 2. Fix interaction

- [x] 2.1 Preserve compact return placement across temporary fitting, manual movement/resizing, and Galgame round trips.
- [x] 2.2 Restore the portrait for exterior, missing, and failed native cursor samples; preserve interactive surfaces.
- [x] 2.3 Integrate pet controls into the expanded chat header, remove history overlap padding, and support keyboard drawer use.

## 3. Verify

- [x] 3.1 Run regression/unit tests, typecheck, build, and Electron interaction/visual checks at compact and expanded sizes.
- [x] 3.2 Inspect screenshots and source diff, run the design detector, and verify implementation against the OpenSpec artifacts.

## 4. Floating-controls conflict follow-up

- [x] 4.1 Reproduce hiding expanded chat with its drawer open and inspect draggable versus clickable regions.
- [x] 4.2 Disable outgoing chat input and native drag regions during the floating-control handoff.
- [x] 4.3 Verify compact/expanded/hidden transitions, current context-menu controls, hover pass-through, and regression checks.
