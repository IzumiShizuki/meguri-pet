## 1. Persist launch preferences

- [x] 1.1 Add enabled-by-default sign-in and administrator settings to host preferences and the shared desktop contract.
- [x] 1.2 Add a narrow startup-settings IPC bridge that applies Windows task configuration and returns actionable failures.

## 2. Apply Windows launch behavior

- [x] 2.1 Add an idempotent PowerShell task helper with optional logon trigger, highest run level, UAC handling, and repository-local logs.
- [x] 2.2 Route the manual batch launcher through the elevated task when administrator launch is enabled.
- [x] 2.3 Synchronize saved launch preferences during desktop startup and surface Windows configuration failures in settings.

## 3. Expose and enable the settings

- [x] 3.1 Add two persisted checkboxes and status feedback to the desktop settings view.
- [x] 3.2 Document the settings and register the requested sign-in task for the current Windows user.
