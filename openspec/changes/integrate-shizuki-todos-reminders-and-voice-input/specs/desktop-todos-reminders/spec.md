## Purpose

Lets the owner manage Shizuki TimePrism todos directly from Meguri and receive due reminders while the native desktop process is running, including when the LLM provider is unavailable.

## ADDED Requirements

### Requirement: User scoped Todo management
Meguri SHALL list the connected Shizuki account's todos and let the user create a todo and mark it complete. It SHALL display the exact title and optional date/time before creation and SHALL show the site's saved result or an explicit failure.

#### Scenario: Create scheduled todo
- **WHEN** the connected user submits a title and exact local reminder date/time in the Todo interface
- **THEN** Meguri creates a Shizuki TimePrism todo with that date/time and an enabled at-time reminder and displays the saved todo

#### Scenario: Complete todo
- **WHEN** the user marks a listed todo complete
- **THEN** Meguri updates that same todo in the connected user's Shizuki account and reflects the returned state

#### Scenario: Site write fails
- **WHEN** Shizuki rejects or times out a Todo write
- **THEN** Meguri shows the failure and does not display an unconfirmed success

### Requirement: Desktop reminder delivery
While connected and running, Meguri SHALL check TimePrism todos for due reminders, show an in-app reminder and an operating-system notification where supported, and avoid repeating the same occurrence after restart. It SHALL avoid alerting for completed todos.

#### Scenario: At-time reminder
- **WHEN** a pending todo's enabled at-time reminder reaches its due time while Meguri is running
- **THEN** Meguri displays the reminder with the title and scheduled time within one polling interval

#### Scenario: Late startup
- **WHEN** Meguri starts within twelve minutes after an unshown reminder's due time
- **THEN** it displays the missed reminder once

#### Scenario: Duplicate poll or restart
- **WHEN** the same todo occurrence is observed again through polling or after an app restart
- **THEN** it is not shown a second time

#### Scenario: App is closed
- **WHEN** Meguri is not running at the reminder time
- **THEN** delivery is deferred until the app next starts within the missed-reminder window; Meguri does not claim to provide always-on delivery

### Requirement: Independent direct path
The Todo interface and due-reminder delivery SHALL operate independently of model inference. Meguri SHALL report Shizuki connection failures in the Todo interface.

#### Scenario: LLM unavailable
- **WHEN** the model provider fails but the Shizuki site is reachable and the account is connected
- **THEN** the user can still list, create, and complete todos through the Todo interface
