## Why

The desktop pet currently fits its portrait into the remaining window area. Resizing the window or expanding chat changes that area and unintentionally scales the character, as reported by the user.

## What Changes

- Give the ambient PNG and Live2D character a fixed rendering surface, independent of viewport dimensions and chat expansion.
- Move the portrait toward the space above expanded chat when available, and keep its top visible in compact windows without reducing its scale.
- Keep chat and window controls responsive and preserve character aspect ratios.

## Capabilities

### New Capabilities

- `ambient-character-sizing`: Stable desktop portrait scale during window resizing and chat visibility changes.

### Modified Capabilities

None. The main specification directory currently contains no published capabilities.

## Impact

Native desktop ambient-stage CSS and its redundant scoped Live2D sizing. No service, preference, API, or dependency changes.
