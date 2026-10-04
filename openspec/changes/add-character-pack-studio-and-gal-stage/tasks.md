## 1. Character Package Foundation

- [x] 1.1 Define renderer-safe character package catalog, manifest, resource, draft, publication, and preview types in the native desktop contract.
- [x] 1.2 Implement main-process character package policy and durable catalog store for validated directory import, draft resources, publication, activation, disable, and restore operations.
- [x] 1.3 Add a narrow, Studio-only Electron preload bridge and safe local asset delivery for catalog summaries and previews.
- [x] 1.4 Add focused policy/store/preload regression tests covering invalid paths, disallowed files, hash mismatches, atomic catalog updates, and absence of generic filesystem access.

## 2. Character Studio

- [x] 2.1 Add the desktop Studio window lifecycle, trusted sender checks, and Stage command that opens the Studio without widening the existing Stage bridge.
- [x] 2.2 Implement renderer character-package API and Pinia store for loading the catalog, importing a directory, adding a draft resource, publishing, activating, disabling, and restoring packages.
- [x] 2.3 Build the Character Studio resource tree, validated preview, detail inspector, draft/published feedback, and accessible import/publish/restore controls.
- [x] 2.4 Add renderer and contract tests for Studio catalog state, preview fallback, import feedback, and package lifecycle controls.

## 3. Galgame Presentation Surface

- [x] 3.1 Refactor the Stage renderer into a stable host with an ambient child and a Galgame child, preserving conversation, artifact, readiness, and preference lifecycle across surface changes.
- [x] 3.2 Add a presentation-surface store and controls for entering/exiting Galgame mode, disabling click-through on entry, and restoring ambient pointer behavior on exit.
- [x] 3.3 Build the Galgame background, character fallback, streamed dialogue, history, composer, cancel, audio preference, and utility controls using the existing conversation and presentation stores.
- [x] 3.4 Add a local presentation recovery ledger and tests proving surface changes preserve session/checkpoint/messages and do not replay consumed one-shot effects.

## 4. Verification

- [x] 4.1 Run OpenSpec strict validation, desktop unit tests, preload smoke tests, type checks, and production build; fix failures introduced by this change.
- [x] 4.2 Use the desktop test harness or Playwright-equivalent visual checks to inspect Studio and Galgame layouts at normal and constrained viewport sizes, then correct material layout, focus, or interaction defects.
