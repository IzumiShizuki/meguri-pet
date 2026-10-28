## Purpose

Fit the transparent desktop window to the compact character composition and keep it reachable when the user switches chat or presentation modes.

## ADDED Requirements

### Requirement: Native frame fits compact companion

The desktop SHALL reduce its native window to a compact character-fitting size when ambient chat is collapsed or hidden. Expanding chat SHALL restore its previously used expanded dimensions. The fixed character rendering size SHALL remain independent of these native window transitions, and the controls SHALL stay near its upper region.

#### Scenario: Collapse or hide chat

- **WHEN** expanded ambient chat is collapsed or hidden
- **THEN** the native window reduces to the compact size and the controls move with the nearby character region
- **AND** the character's screen center and top are preserved unless display edges require repositioning

#### Scenario: Expand chat again

- **WHEN** compact ambient chat is expanded
- **THEN** the saved expanded dimensions are restored without scaling the portrait

#### Scenario: Legacy oversized compact bounds

- **WHEN** the desktop starts with old saved oversized bounds and collapsed chat
- **THEN** those bounds are fitted to the compact composition without discarding the remembered expanded dimensions

### Requirement: Presentation and display boundaries

The desktop SHALL retain an independent roomy Galgame layout and restore the ambient layout on return. Host window fitting SHALL remain inside the matching display work area where possible, including negative display coordinates. Only the trusted stage renderer SHALL request a named presentation-surface switch.

#### Scenario: Galgame round trip

- **WHEN** the user enters and exits Galgame
- **THEN** Galgame uses its own roomy dimensions and the ambient companion returns to its previous layout

#### Scenario: Display edge or multiple monitors

- **WHEN** fitting would place the window outside its matching display work area
- **THEN** the host clamps the resulting bounds without double-applying display scale factors

#### Scenario: Invalid renderer or surface

- **WHEN** another renderer or an invalid surface value requests a presentation switch
- **THEN** the host rejects that switch
