## Why

Generated-file bubbles appear beside the desktop pet, but users cannot reliably activate them or dismiss them. This blocks the primary purpose of the artifact notification and leaves stale bubbles covering the pet.

## What Changes

- Make each generated-file bubble a reliably hit-testable interactive control in both the normal page and overlay layouts.
- Ensure activating a bubble opens the allow-listed artifact and removes that bubble after a successful activation.
- Provide a visible, independently clickable dismiss control that always removes the bubble without attempting to open the file.
- Add regression coverage for activation, dismissal, keyboard activation, and failed-open behavior.

## Capabilities

### New Capabilities

- `desktop-artifact-bubbles`: Defines the interactive lifecycle of generated-file bubbles in the desktop pet UI.

### Modified Capabilities

## Impact

- `apps/desktop-airi/web/index.html` and a small browser-side interaction module/test seam.
- No server or artifact allow-list changes are required; existing Electron IPC and loopback artifact URLs remain the source of file opening.
- The change affects only generated-file notification bubbles and their DOM interaction behavior.
