## Why

Expanded chat crowds the upper-right controls and reserves an unnecessary strip through the message history. Near screen edges, temporarily expanding a panel can permanently shift the pet left, and hover hiding can remain stuck after the cursor leaves the native window.

## What Changes

- Group expanded-chat actions and pet controls into a compact, accessible header while preserving the existing conversation palette and all actions.
- Restore the compact pet position after temporary panel expansion, including clamping near screen edges and intentional movement while expanded.
- Resolve every polled cursor position, including positions outside the window and failed polling, so hover hiding always recovers.
- Add focused native-window and renderer regression coverage.

## Capabilities

### New Capabilities

- `stable-desktop-chat-interaction`: Expanded header controls, reversible temporary window fitting, and reliable hover recovery.

### Modified Capabilities

None.

## Impact

Changes are limited to the desktop Electron stage layout controller, Vue stage/chat/control components, related styles, and targeted tests. No dependencies, external services, or credentials change.
