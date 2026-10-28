## Why

Expanding and then collapsing the desktop chat leaves a compressed conversation panel and excess transparent space. The user wants MeaPet-style reply bubbles in the small pet window and a larger bubble when using Todo or other tools, with consistent character scale.

## What Changes

- Replace the collapsed history panel with a small latest-reply bubble and compact composer; clicking the reply opens the complete conversation.
- Display conversation history, Todo, reminder review, attachments, skills, and work in a larger bubble beside the character when space permits.
- Fit the native window to the active surface, preserving the fixed PNG/Live2D portrait and its screen anchor across repeated toggles.
- Migrate legacy tall expanded bounds and return to the compact window when temporary tools close, preserving drafts and conversation state.

## Capabilities

### New Capabilities

- `desktop-companion-bubbles`: Compact replies, expanded tool bubbles, and stable window/portrait geometry.

### Modified Capabilities

None. There are no published specifications in openspec/specs.

## Impact

Desktop renderer components/styles, shared stage geometry, native layout controller and presentation-surface IPC validation, isolated visual checks and layout regression tests. No additional dependencies or service changes.
