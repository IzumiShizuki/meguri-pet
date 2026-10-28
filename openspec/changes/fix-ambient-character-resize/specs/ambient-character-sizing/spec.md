## Purpose

Keep the desktop companion visually stable when its surrounding window or chat panel changes size, while preserving the character's proportions and visibility.

## ADDED Requirements

### Requirement: Ambient character scale is independent of layout

The desktop stage SHALL keep a fixed character rendering size when the window is resized or chat is shown, hidden, expanded, or collapsed. PNG, Live2D, and Live2D's PNG fallback SHALL use the same rendering surface.

#### Scenario: Window becomes narrower or shorter

- **WHEN** the user reduces the desktop window width or height within supported native bounds
- **THEN** the character rendering surface retains its width and height
- **AND** the character is repositioned without being scaled down

#### Scenario: Chat layout changes

- **WHEN** the user expands, collapses, shows, or hides the chat panel
- **THEN** the character retains its rendering size
- **AND** the chat panel continues to adapt to the available window space

### Requirement: Character proportions and top remain visible

The desktop stage SHALL preserve the portrait's aspect ratio and keep the top of its rendering surface inside the supported window. Expanded chat SHALL remain usable in front of the character when space is insufficient to separate them.

#### Scenario: Expanded chat in the smallest supported window

- **WHEN** chat is expanded in the smallest supported desktop window
- **THEN** the top of the character surface remains inside the window
- **AND** chat controls remain in front of the lower portrait rather than forcing the character to shrink

#### Scenario: Renderer or image changes

- **WHEN** the user changes the PNG outfit or activates Live2D
- **THEN** the renderer uses the same fixed surface and preserves its own aspect ratio
