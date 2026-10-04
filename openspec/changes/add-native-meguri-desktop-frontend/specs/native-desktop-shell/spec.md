## Purpose

Provide a first-party Meguri desktop process that owns startup visibility, window lifecycle, security-sensitive host operations, and readiness reporting independently from the AIRI desktop application.

## ADDED Requirements

### Requirement: Startup readiness is visible before the pet stage
The desktop application SHALL open a dedicated startup view while required local capabilities are being checked and SHALL reveal the pet stage only after the frontend and minimum conversation path are ready.

#### Scenario: All required services become ready
- **WHEN** the desktop application confirms that the gateway, Core, required character asset, and frontend renderer are ready
- **THEN** the startup view reports completion and transitions to the pet stage

#### Scenario: A required service is unavailable
- **WHEN** a required readiness check fails or exceeds its deadline
- **THEN** the startup view remains available, identifies the failed capability, and offers a retry without exposing a blank pet window

### Requirement: Desktop privileges are exposed through a narrow host interface
The desktop application MUST keep filesystem paths, window mutation, and operating-system file opening outside the web renderer and SHALL expose only explicitly supported operations through its preload interface.

#### Scenario: Renderer requests an allowed generated file
- **WHEN** the renderer asks to open an existing passive document returned by the trusted artifact feed
- **THEN** the host validates the path against the configured Meguri report and output roots before opening it with the default application

#### Scenario: Renderer requests an unsupported path
- **WHEN** the renderer asks to open a path outside allowed roots or with an executable or script-like extension
- **THEN** the host rejects the request without opening the path

### Requirement: Pet window state is owned by Meguri
The desktop application SHALL own the transparent pet window's visibility, always-on-top behavior, size, position, and persisted bounds without delegating those decisions to AIRI.

#### Scenario: User moves or resizes the pet window
- **WHEN** the user changes the pet window bounds and exits normally
- **THEN** the next launch restores valid visible bounds on an available display

#### Scenario: Saved bounds are no longer visible
- **WHEN** persisted bounds do not intersect any current display work area
- **THEN** the desktop application places the pet window in a visible default position
