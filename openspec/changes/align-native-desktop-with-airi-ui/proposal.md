## Why

The first native Meguri desktop slice is operational but does not yet preserve the AIRI fork experience users are replacing. Its persistent top status/control row reads like a title bar, its purple chat dock differs from AIRI in color and proportions, and it lacks AIRI's collapsible floating controls and usable settings surface.

## What Changes

- Replace the persistent top status/control chrome with an AIRI-familiar bottom-right collapsible controls island and unobtrusive transient status treatment.
- Match the current AIRI fork's embedded chat dock geometry, warm-light/stone-dark palette, compact and expanded heights, and bottom inset.
- Add a reusable, independent Meguri settings window opened from the controls island.
- Provide working settings for appearance, chat presentation, character presentation, window behavior, speech/TTS, shortcuts, and local service status.
- Add working controls for moving, chat visibility, outfit selection, settings, theme, always-on-top, centering, refresh, and hide/quit behavior.
- Preserve the first-party Meguri startup flow, canonical conversation protocol, safe artifact boundary, and future window-placement seams.
- Keep AIRI-only providers, plugin management, updater, and unsupported model renderers out of the native settings UI until corresponding Meguri capabilities exist.

## Capabilities

### New Capabilities

- `airi-desktop-ui-parity`: Defines the AIRI-familiar frameless stage, floating controls island, embedded chat presentation, and functional Meguri settings experience.

### Modified Capabilities

None.

## Impact

- Changes `apps/desktop-meguri` Electron window lifecycle, preload contract, Vue components, presentation state, and visual tokens.
- Adds a settings renderer view and persisted non-sensitive desktop preferences.
- Adds focused unit and renderer tests plus Windows desktop visual acceptance checks.
- Does not import AIRI stores or change Java Core, gateway security, canonical conversation events, or the AIRI fallback launcher.
