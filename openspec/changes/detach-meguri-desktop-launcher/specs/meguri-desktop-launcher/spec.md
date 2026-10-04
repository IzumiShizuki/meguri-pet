## Purpose

Provides a Windows launch path for the native Meguri desktop that lets the UI run independently and keeps startup and runtime errors available for diagnosis.

## ADDED Requirements

### Requirement: The desktop launcher starts the UI independently
The Windows Meguri desktop launcher SHALL start the native desktop as an independent process and return after initiating it, without leaving a visible command window open for the lifetime of the desktop UI.

#### Scenario: User starts Meguri from the desktop launcher
- **WHEN** the user launches the native Meguri desktop entrypoint
- **THEN** the desktop UI continues running independently after the launcher process exits

### Requirement: Desktop startup and runtime errors are logged
The launcher SHALL write its startup errors and the native desktop process's standard error to dedicated files in the repository-root `log/` directory.

#### Scenario: Launcher startup fails
- **WHEN** a required launcher step fails before the desktop process starts
- **THEN** the failure details are appended to `log/desktop-launcher.log`

#### Scenario: Native desktop reports an error
- **WHEN** the native desktop process writes an error to standard error
- **THEN** the error is recorded in `log/desktop-electron.err.log` after the launcher exits
