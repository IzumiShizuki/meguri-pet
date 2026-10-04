## Why

Meguri currently depends on a heavily customized AIRI Stage Tamagotchi fork for its user-facing desktop experience. Continued product work—especially a branded startup experience, custom desktop interactions, file workflows, and future window attachment—would spread more Meguri behavior through AIRI internals and make both upstream updates and Meguri development increasingly expensive.

Meguri needs an owned desktop frontend that can evolve independently while preserving the useful behavior and visual familiarity of the current AIRI fork during a gradual migration.

## What Changes

- Add a first-party `desktop-meguri` Electron and Vue application inside this repository.
- Recreate the current AIRI-derived transparent pet stage, floating controls, compact chat dock, character state presentation, and generated-file bubbles with Meguri-owned code and styling.
- Add a dedicated startup window that reports Core, desktop gateway, local TTS, assets, and frontend readiness before revealing the pet window.
- Connect the frontend to the existing loopback gateway and canonical turn/SSE protocol instead of AIRI's provider/store internals.
- Expose a narrow preload interface for window controls, safe generated-file actions, health status, and later window placement adapters.
- Keep the AIRI fork runnable as a migration reference and fallback; this change does not delete it or copy its full monorepo into Meguri.
- Establish an incremental parity roadmap for conversation history, attachments and resource selection, daily briefings, document approvals, settings, voice input, and optional advanced character rendering.

## Capabilities

### New Capabilities

- `native-desktop-shell`: Own the Meguri Electron lifecycle, startup readiness window, transparent pet window, safe preload interface, and local service coordination.
- `meguri-desktop-stage`: Present a Meguri-branded pet stage with AIRI-familiar composition, character state, floating controls, responsive chat dock, and persisted user-facing UI state.
- `meguri-desktop-conversation`: Create and follow canonical Meguri turns, render streamed conversation state, recover sessions, and surface safe generated artifacts without depending on AIRI stores.

### Modified Capabilities

None.

## Impact

- Adds a new workspace app under `apps/desktop-meguri` and frontend dependencies for Vue, Vite, Pinia, Electron, and focused tests.
- Reuses the existing `apps/desktop-airi` loopback gateway initially, with shared gateway behavior extracted only where duplication would otherwise occur.
- Adds new local startup commands without replacing the existing AIRI launcher during the migration phase.
- Does not change Java Core wire contracts, remote deployment, memory authority, or AIRI's MIT license boundary.
