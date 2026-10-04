## Why

The native Meguri desktop client currently has a transparent pet stage and a shared conversation runtime, but users cannot safely import and manage character presentation resources or enter an immersive Galgame surface without breaking conversation continuity. This change turns the existing desktop shell into a usable character-presentation product while preserving Core as the authority for conversation, memory, and runtime state.

## What Changes

- Add a Character Studio for viewing, previewing, adding, disabling, and rolling back local character presentation resources.
- Add validated Character Pack import flows for packaged assets, raw resource directories, and incremental resource additions, with draft-first review and atomic publication.
- Add a Galgame Stage surface that can open from and return to the ambient desktop pet while continuing the same active conversation and presentation snapshot.
- Add replay-safe presentation segment handling so voice, one-shot motions, and interruption state remain correct across Stage transitions and reconnects.
- Preserve text-first conversation behavior when renderer, audio, or imported resources are unavailable.

## Capabilities

### New Capabilities
- `desktop-character-pack-management`: Import, inspect, preview, edit, publish, disable, and restore versioned character presentation resources in the native desktop client.
- `desktop-galgame-presentation`: Present the existing Meguri conversation in an immersive Galgame surface that preserves the active session and returns cleanly to ambient mode.
- `desktop-presentation-recovery`: Apply presentation segments, interruption generations, and replay policies without duplicating local visual or audio effects.

### Modified Capabilities

- None.

## Impact

- Affects `apps/desktop-meguri` renderer state, components, Electron preload/main contracts, and frontend tests.
- May add local-only presentation package metadata and safe asset inspection utilities; imported resources must never become Core tool capabilities or executable content.
- Reuses the existing desktop conversation client and event-stream contracts without changing Meguri Core session, memory, relationship, or capability authority.
