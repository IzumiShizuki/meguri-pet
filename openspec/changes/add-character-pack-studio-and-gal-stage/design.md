## Context

See `proposal.md` for motivation and the capability specs for the behavior contract. The native desktop application has a transparent ambient stage, one shared Pinia conversation store, an existing safe Electron preload pattern, and a generator-produced PNG/JPG asset directory with a hashed `manifest.json`. It has no character package catalog, safe import boundary, Studio window, Galgame surface, or presentation replay state.

The desktop worktree is already active and contains user changes. This increment must leave the Core conversation protocol and existing AIRI behavior untouched, and it must not expose arbitrary filesystem access to the renderer.

## Goals / Non-Goals

**Goals:**

- Deliver a working, local-only PNG/JPG character-package vertical slice: validate a directory, stage it as a draft, preview it, publish it, activate it, and restore an earlier published package.
- Add a dedicated Character Studio window with a narrow Electron bridge rather than putting filesystem access in Vue.
- Add a Galgame surface within the existing Stage renderer so it shares the existing conversation and presentation stores.
- Preserve text-first behavior and prevent surface rebuilding from replaying local one-shot effects.

**Non-Goals:**

- Live2D, VRM, Spine, video, TTS provider integration, lip-sync, archive extraction, community distribution, cloud synchronization, or Core-side presentation selection.
- Replacing the current sprite URL resolver for the built-in default package in this slice.
- Creating a separate Galgame story engine, progression store, branch save system, or relationship state.

## Decisions

### Directory-based PNG/JPG import is the first package format

The first import path accepts a selected directory containing the existing generator-compatible `manifest.json` and declared PNG/JPG resources. The importer copies accepted content into an app-owned package root, validates every declared relative path, enforces count/byte limits, rejects symlinks and unsupported files, and validates declared SHA-256 checksums when present.

The existing generated asset release is a real deterministic fixture for this format. Archive support is deferred: the workspace does not currently include a vetted archive parser, and shell extraction would add zip-bomb, link, and path-safety risks without improving the first vertical slice.

Alternative considered: accepting arbitrary folders and inferring assets. Rejected because an explicit manifest gives safe package identity, declared assets, repeatable validation, and a clear publication boundary.

### Main process owns package files and policy

`character-pack-store` and `character-pack-policy` live in the Electron main process. They persist catalog metadata separately from copied package content, write state through a temporary file plus atomic rename, and retain prior published versions. Renderer-visible resources are exposed only as generated `asset://` URLs or safe data, never as absolute paths.

The narrow bridge offers named operations only: list catalog, choose a directory, import directory as a draft, add a supported draft resource, publish a draft, activate a published package, disable a package, and restore a retained version. Each operation is restricted to the Studio window. The stage renderer can consume published catalog summaries and safe preview URLs but cannot import or browse files.

Alternative considered: reuse chat attachment inspection. Rejected because that policy authorizes one user-selected chat attachment, does not own durable package copies, and intentionally lacks package manifest/hash semantics.

### Studio is a dedicated opaque Electron window

The Stage BrowserWindow remains focused on the transparent pet and active chat. Character Studio opens as an opaque normal window using the existing settings-window lifecycle and trusted-renderer checks. Its renderer uses a catalog service and Pinia store modeled after the existing Skills workspace: selected item, one serialized operation, loading state, notice/error, and explicit draft/publication states.

Alternative considered: embed Studio in the transparent stage. Rejected because filesystem curation is a dense work-focused flow and would complicate ambient pointer pass-through and desktop composition.

### Galgame mode is a surface inside one stable Stage host

The existing Stage view keeps conversation, artifact polling, readiness, and preference lifecycle mounted. It renders an ambient child or a Galgame child based on a local presentation-surface store. The Galgame child uses the existing `useConversationStore`, `usePresentationStore`, composer, history, and terminal-state behavior; it never calls `newConversation` or creates another stream subscription.

Entering Galgame mode disables click-through and renders an opaque interactive surface. Leaving it returns control to the existing ambient pointer policy. The first implementation uses a selected package background and image portrait when available, with neutral layout fallback when they are not.

Alternative considered: a second Galgame BrowserWindow. Rejected because it would complicate input focus, duplicate lifecycle handling, and increase the risk of a divergent conversation state.

### Presentation recovery is a local display ledger, not a new Core protocol

The initial `presentation-surface` store records the active surface, last resolved package snapshot revision, and consumed local one-shot cue identifiers. It reconstructs current visual state when a child surface mounts and suppresses previously consumed one-shot effects. No new server event type is needed for this slice; future audio/Live2D work can extend the ledger with segment and interruption generations.

Alternative considered: storing playback status in the conversation event history. Rejected because local animation/audio consumption is device-specific presentation state and must not change the Core event source of truth.

## Risks / Trade-offs

- [A package manifest can reference malicious or missing local files] -> Validate all paths against the selected root, reject symlinks and disallowed extensions, copy only validated files into the app-owned root, and keep the active catalog unchanged on any failure.
- [A large package can make import or preview slow] -> Enforce conservative item and byte limits, perform validation in the main process, and report progress/errors through a single serialized operation.
- [A new Studio bridge can widen Electron attack surface] -> Restrict IPC to named methods, trusted Studio sender checks, structured-clone summaries, and no generic read/write/list APIs.
- [Switching Stage surfaces could restart the conversation] -> Keep lifecycle stores mounted in the Stage host and cover session/checkpoint/message preservation with tests.
- [A package update can visually change a running reply] -> Bind the running surface to its current catalog revision and resolve only later interactions from a newly active revision.
- [First release does not preview Live2D assets] -> Catalog them as unsupported rather than loading them; add a later renderer-specific capability once an approved runtime exists.

## Migration Plan

1. Add policy/store tests and the main-process catalog bridge behind no automatic import or activation.
2. Add Studio view and open command; users explicitly import and publish packages.
3. Add the Stage surface switch with safe fallback and no alteration to existing default sprite behavior.
4. Enable a published package for Galgame background/portrait selection only after catalog and preview tests pass.
5. Roll back by disabling a published package or restoring the retained previous catalog pointer. The existing built-in sprite resolver remains available throughout.

## Open Questions

- The initial manifest maps generator-produced sprite/background/UI groups. A later format revision can add user-authored semantic descriptions and explicit outfit-expression mappings without changing the safe directory ownership model.
