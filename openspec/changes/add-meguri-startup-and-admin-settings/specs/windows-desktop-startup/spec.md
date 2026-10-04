## Purpose

Lets users control whether Meguri starts at Windows sign-in and whether its desktop process runs with administrator privileges.

## ADDED Requirements

### Requirement: Startup preferences are visible and persistent
The Windows settings view SHALL provide separate persistent controls for starting Meguri at sign-in and running Meguri as administrator. Both controls SHALL be enabled by default for this local desktop setup.

#### Scenario: User opens startup settings for the first time
- **WHEN** no saved startup preferences exist
- **THEN** both startup controls are checked

### Requirement: Sign-in startup follows the saved setting
When sign-in startup is enabled, Windows SHALL start Meguri after the current user signs in. When it is disabled, Windows SHALL NOT start Meguri at sign-in.

#### Scenario: User enables sign-in startup
- **WHEN** the user saves sign-in startup as enabled
- **THEN** Meguri is configured to launch at the next interactive sign-in

#### Scenario: User disables sign-in startup
- **WHEN** the user saves sign-in startup as disabled
- **THEN** the Meguri sign-in trigger is removed while the administrator preference remains independent

### Requirement: Administrator startup uses Windows elevation
When administrator launch is enabled, automatic and manual launches through the Meguri desktop launcher SHALL run the desktop process with the current user's highest available privileges. Windows approval or task configuration failures SHALL be reported in settings and SHALL NOT be shown as successfully applied.

#### Scenario: Administrator launch is enabled
- **WHEN** the user saves administrator launch as enabled
- **THEN** Windows configures the Meguri launcher to run with the highest available privileges and the setting takes effect on the next launch

#### Scenario: Windows rejects administrator task configuration
- **WHEN** the user cancels elevation or Windows cannot register the task
- **THEN** settings show the failure and retain the previously applied configuration
