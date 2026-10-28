## Purpose

Make companion chat, filenames, input hints, and controls easy to read using coherent fresh colors across light and dark appearances.

## ADDED Requirements

### Requirement: Readable companion colors

The companion SHALL use coherent foreground/background colors for text, file tags, input hints, and actions. Normal readable text SHALL have at least 4.5:1 contrast against its opaque theme surface. New installations SHALL use a fresh light mint appearance, and existing explicit theme choices SHALL remain selectable.

#### Scenario: File messages and inputs

- **WHEN** file messages or the composer are shown in light or dark appearance
- **THEN** file labels and placeholder text use readable theme colors rather than legacy orange/brown combinations

#### Scenario: Fresh appearance selection

- **WHEN** the user selects the light mint palette
- **THEN** chat uses a pale surface, dark text, and a restrained mint action color consistently in the dock and floating controls

#### Scenario: Theme and palette changes

- **WHEN** the user switches between supported palettes or appearances
- **THEN** the existing controls and chat remain usable without hardcoded colors conflicting with that selection
