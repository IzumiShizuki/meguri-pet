## Purpose

Separates the user's saved launch preference from the Windows scheduled-task state, so permission prompts do not discard a preference that the normal user profile can store.

## ADDED Requirements

### Requirement: Startup preferences are saved separately from Windows task application
The desktop SHALL persist a valid startup preference in the current user's preferences before applying the corresponding Windows scheduled-task change. If the local preference write fails, the desktop SHALL leave the Windows task unchanged. If Windows rejects or cannot apply the task change, the desktop SHALL retain the saved preference, report task application as pending or failed, and SHALL NOT report the task as applied.

#### Scenario: Windows applies the requested task state
- **WHEN** the local preference write succeeds and Windows applies the requested sign-in and privilege configuration
- **THEN** the saved preferences reflect the request and the task status reports success

#### Scenario: User cancels UAC while disabling administrator startup
- **WHEN** the user disables administrator startup for an existing highest-privilege task and cancels the required UAC prompt
- **THEN** the requested preference remains saved, the status says the Windows task is still unapplied, and the previous scheduled task remains unchanged

#### Scenario: Task Scheduler rejects an update
- **WHEN** the local preference write succeeds but Windows cannot register or update the scheduled task
- **THEN** the requested preference remains saved and the settings view offers a retry while identifying the OS task as unapplied

#### Scenario: Local preference storage fails
- **WHEN** the desktop cannot write the requested preference to the current user's state file
- **THEN** it keeps the previous preference, does not attempt the Windows task update, and reports a local save failure
