## Why

The supplied screenshots show low-contrast orange filenames on brown chips and a compact chat state inside an oversized transparent window, leaving the controls far above the character. The user requests a fresh readable palette and a surrounding window that follows the compact character layout.

## What Changes

- Use a light mint default with dark readable text and consistent semantic colors for file chips, inputs, actions, and floating controls; keep the existing selectable palettes and dark mode usable.
- Fit the ambient native window to its fixed character surface when chat is collapsed or hidden, and restore the user's expanded window dimensions when chat expands.
- Anchor mode transitions to the character's on-screen position, keep windows reachable within the current display work area, and retain a roomy Galgame layout.
- Apply the requested light/mint choice to this user's local desktop preferences while preserving other settings.

## Capabilities

### New Capabilities

- `readable-desktop-colors`: Consistent readable companion UI colors with a fresh light default.
- `compact-companion-window`: Native window fitting and position-preserving ambient/chat mode transitions.

### Modified Capabilities

None; published main specifications are absent.

## Impact

Desktop palette/CSS/default preferences, shared stage geometry, native window lifecycle and state persistence, and a restricted stage-only presentation-surface IPC. No backend services or new dependencies.
