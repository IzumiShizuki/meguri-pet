## Context

See `proposal.md` for motivation. The current visible product is the full AIRI Stage Tamagotchi Electron application in `D:\program\airi-meguri`. Its Meguri branch contains five custom commits over the pinned AIRI base and changes approximately ninety files across the Electron app, shared stage UI, Live2D, chat orchestration, and localization. The related AIRI surface is too large to vendor wholesale, while the existing `apps/desktop-airi` diagnostic shell in this repository already owns the loopback gateway, safe artifact feed, restricted OS file operations, canonical turn recovery, and PNG assets needed for a first native slice.

The repository is a pnpm workspace using Node 24. Existing Core, protocol, client SDK, renderer contracts, gateway, local TTS, and sprite mapping remain authoritative. The worktree contains unrelated context-runtime and observation changes; implementation must avoid rewriting those files.

## Goals / Non-Goals

**Goals:**

- Establish a production-shaped, testable Electron/Vue application owned by Meguri.
- Match the current AIRI fork's recognizable desktop composition closely enough that users can transition gradually.
- Deliver an end-to-end first slice: startup readiness, transparent stage, canonical streaming chat, character cues, artifact bubbles, safe file opening, and persisted desktop state.
- Create explicit seams for later parity work without importing AIRI stores or copying its monorepo.

**Non-Goals:**

- Reimplement AIRI's Live2D, VRM, Spine, plugin host, settings catalog, VAD, or updater in the first slice.
- Delete or mutate the AIRI fork, or make it impossible to fall back to the current launcher.
- Change Core persona, memory, retrieval, document security, or deployment behavior.
- Implement native HWND following in the first slice; window attachment will consume the existing observation-domain contract later.

## Decisions

### 1. Build a separate `apps/desktop-meguri` application

The app will use Electron for the main/preload processes and Vue 3 with Pinia for the renderer. The application owns its windows, product routes, visual tokens, and renderer state.

Alternative considered: keep renaming and modifying `apps/desktop-airi`. Rejected because that folder still serves as the stable gateway and diagnostic fallback, and mixing migration code into it would make rollback and comparison harder.

### 2. Reuse Meguri contracts by source during the first slice

The renderer will import the local client SDK, protocol, and focused pure helpers through workspace packages or source aliases. The canonical HTTP/SSE envelope remains the only conversation interface.

Alternative considered: copy the larger AIRI `meguri-airi-adapter`. Rejected because it embeds AIRI provider types and maintains a second protocol implementation. Any generally useful behavior should deepen the existing Meguri SDK instead.

### 3. Keep a single local gateway process during migration

The existing Node gateway remains the credential and local-file boundary. It will be extended to recognize the first-party `meguri` desktop identity while preserving the existing `airi` identity and its current routes. Static frontend delivery is owned by Vite/Electron rather than the gateway.

Alternative considered: create a second gateway with copied security code. Rejected because duplicated allow-lists and token handling would create a dangerous shallow boundary.

### 4. Separate startup and stage windows

Electron main creates a compact opaque startup window first. A readiness coordinator queries gateway/Core health and a canonical sprite, reports state to the startup renderer, then creates and reveals the transparent always-on-top stage. Bounds and presentation preferences are persisted outside the renderer's transient state.

Alternative considered: show a loading overlay inside the transparent stage, as AIRI currently does. Rejected because backend failures produce a poor blank-window experience and conflate service orchestration with character rendering.

### 5. Preserve the AIRI composition, not AIRI's visual implementation

The first renderer uses the same high-level layout: character-centered transparent stage, small floating control island, status indicator, artifact bubbles, and bottom compact/expanded chat dock. Styling uses Meguri-owned tokens: dusk-indigo glass, soft peach status accents, blue file actions, rounded floating surfaces, and restrained ambient rings behind the sprite. The signature element is a faint orbital readiness ring that becomes the stage presence halo after startup.

Copying AIRI Vue components or its broad UnoCSS stack was considered and rejected. Similarity is expressed through spatial behavior and interaction hierarchy, while implementation and tokens remain owned locally.

### 6. Model renderer state by domain stores

Use focused stores for runtime readiness, conversation/session state, stage presentation, and artifacts. Electron-only effects stay behind the preload interface. A renderer adapter converts canonical turn events into store mutations and character cues.

Alternative considered: one large page component similar to the existing diagnostic HTML. Rejected because future parity features would accumulate unrelated lifecycle and IO concerns in a shallow module.

### 7. Make parity incremental and explicit

The first slice includes the features necessary for daily use. Later slices will add, in order: attachments/resource selection and document approval; daily briefings and settings; voice input and TTS controls; screen-edge snapping and native window attachment; optional Live2D adapter. Each slice receives its own OpenSpec delta and regression tests.

## Risks / Trade-offs

- [Risk] The first slice will not match every AIRI setting or renderer. → Keep the AIRI launcher intact and surface a clear parity roadmap rather than pretending incomplete controls exist.
- [Risk] Running Vite source and Electron together can obscure startup failures. → Use a readiness coordinator with explicit states and focused tests; packaged builds load only built assets.
- [Risk] The old gateway names and headers encode AIRI identity. → Accept both desktop client identifiers during migration and preserve stable user identity while keeping session validation strict.
- [Risk] Generated-file opening is security-sensitive. → Reuse the existing allow-listed path policy and expose no generic shell or filesystem method to the renderer.
- [Risk] Visual similarity can drift without browser inspection. → Capture deterministic screenshots at desktop-sized and compact viewports and verify the primary interactions with Playwright.
- [Risk] New frontend dependencies increase workspace installation cost. → Keep dependencies focused and avoid importing AIRI's 23-package desktop dependency graph.

## Migration Plan

1. Scaffold the new application and define shared window/readiness contracts.
2. Extend the gateway identity gate for the native Meguri client without removing AIRI support.
3. Implement and test the startup window and transparent stage lifecycle.
4. Implement the AIRI-familiar Vue stage, canonical conversation store, PNG cue handling, and artifact actions.
5. Add unit and browser interaction tests, build the app, and visually inspect screenshots.
6. Add a separate native launcher command; keep `start-meguri-airi` unchanged as fallback.
7. Continue parity through later OpenSpec changes and switch the default launcher only after the native frontend covers the user's regular workflow.

Rollback is stopping the native launcher and using the unchanged AIRI fork launcher. Shared gateway additions remain backward compatible with the AIRI client.

## Open Questions

- Whether the eventual advanced character renderer should consume AIRI's Live2D package, a smaller extracted adapter, or another engine can wait until a real Meguri Live2D asset is available.
- Exact screen-edge snapping thresholds and native target-window discovery belong to the later window-placement slice and do not affect this frontend foundation.
