## Purpose

Give native Meguri desktop users an always-available indication of process presence and a local recovery entry when the pet is hidden, obscured or misplaced.

## ADDED Requirements

### Requirement: Notification-area presence follows desktop ownership

The owning native desktop process SHALL keep one recognizable Meguri notification-area icon while running, independently of stage visibility. Additional instances MUST reuse the owner instead of creating another entry. Explicit exit MUST remove the entry. The application MUST respect Windows management of taskbar and overflow placement.

#### Scenario: Hidden pet still has a presence entry
- **WHEN** the user hides the pet while Meguri runs
- **THEN** the notification-area entry remains available

#### Scenario: Relaunch while already running
- **WHEN** another native instance starts with the same profile
- **THEN** it exits without creating another entry and the owner reveals the desktop

#### Scenario: Explicit exit
- **WHEN** the user selects exit in the tray menu or desktop controls
- **THEN** Meguri shuts down and destroys its tray entry

### Requirement: Status describes current desktop availability

The icon tooltip and a disabled menu status item SHALL describe starting, required-service unavailability, running with a visible pet, or running with a hidden/minimized pet. They MUST NOT imply that an icon proves current remote connectivity, reminders, or message delivery.

#### Scenario: Startup and failed checks
- **WHEN** startup checks are pending or a required check fails
- **THEN** the entry identifies starting or service unavailability respectively and remains usable

#### Scenario: Visibility changes
- **WHEN** a ready pet is shown, hidden, minimized or restored
- **THEN** the displayed status reflects its visibility

### Requirement: Tray actions recover the desktop

A click or double click SHALL recover the desktop rather than toggle it hidden. Recovery MUST restore a minimized pet, center it on its current available display and show/focus it while preserving the always-on-top preference. The menu SHALL offer show pet, open chat, settings and exit using only local application actions. While required services are unavailable, recovery SHALL show the startup status window. Closing the last window without explicit exit MUST keep the tray entry able to recreate the appropriate window.

#### Scenario: Lost or hidden pet
- **WHEN** the user clicks the icon while a ready pet is hidden, minimized or obscured
- **THEN** the pet is restored, centered, shown and focused
- **AND** another click keeps it visible

#### Scenario: Open chat or settings
- **WHEN** the user selects open chat or settings
- **THEN** Meguri reveals the pet with expanded chat or opens its existing settings window respectively
- **AND** no reminder or external message is created

#### Scenario: Startup recovery
- **WHEN** the pet is unavailable because required startup checks have not succeeded
- **THEN** clicking the icon reveals the startup status window

#### Scenario: Closed window recovery
- **WHEN** the last window closes while the tray-owning process is still running
- **THEN** the tray remains and clicking it recreates the appropriate desktop window

### Requirement: Safe tray lifecycle and failure behavior

The application SHALL retain the tray resource and clean its event handlers on exit. Local action failures MUST be caught and recorded without unhandled promise rejection. Tray creation failure MUST be logged while leaving the ordinary desktop launch usable. User data, elevation settings and existing reminder ownership MUST remain intact.

#### Scenario: Action failure
- **WHEN** a tray action rejects while Meguri runs
- **THEN** the failure is recorded and subsequent actions remain usable

#### Scenario: Tray creation failure
- **WHEN** the OS cannot register the tray entry
- **THEN** the error is recorded and ordinary desktop startup continues
