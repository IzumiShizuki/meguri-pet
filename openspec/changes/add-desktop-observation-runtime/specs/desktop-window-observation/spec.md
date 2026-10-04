## Purpose

为 Meguri 提供可验证、可暂停且隐私优先的桌面窗口观察边界，使桌宠能够知道自己附着在哪个窗口，同时不会在观察过程中自主移动或越过用户设置的隐私限制。

## ADDED Requirements

### Requirement: Window identity and attachment state

The system SHALL represent an attached window with a stable window identifier, process identifier, application name, title, screen bounds, relative anchor, relative offset, and visibility state. An attachment SHALL be associated with the active user and desktop client context.

#### Scenario: Attach to a visible application window

- **WHEN** the user explicitly attaches Meguri to a visible application window
- **THEN** the system records the window identity and relative attachment geometry
- **AND** the attachment becomes the only window eligible for ordinary observation

#### Scenario: Window identity is no longer available

- **WHEN** the attached window is closed or its identity can no longer be resolved
- **THEN** the system marks the attachment unavailable
- **AND** it does not silently attach Meguri to another window

### Requirement: Follow attached window geometry without autonomous movement

The system SHALL preserve the user-selected relative anchor and offset when the attached window moves, resizes, maximizes, or restores. The observation runtime MUST NOT select a new window or move Meguri without an explicit user action or an update originating from the attached window geometry.

#### Scenario: Attached window moves

- **WHEN** the attached window changes its screen position
- **THEN** the system emits an attachment geometry update using the stored anchor and offset
- **AND** Meguri remains attached to the same window

#### Scenario: Attached window is minimized

- **WHEN** the attached window becomes minimized or invisible
- **THEN** the system changes the attachment visibility state to unavailable for observation
- **AND** no screen observation request is generated

#### Scenario: Attached window is restored

- **WHEN** the same attached window becomes visible again
- **THEN** the system restores observation eligibility for that window
- **AND** it does not treat the restore as permission to observe any other window

### Requirement: Enforce local observation privacy policy

The system SHALL evaluate an observation request against a local policy containing an enabled flag, privacy mode, application blacklist, title or pattern blacklist, and sensitive-window defaults. A denied request MUST fail closed and MUST NOT produce a screen capture request or screen semantic context.

#### Scenario: Observation is paused

- **WHEN** the user pauses observation or enables privacy mode
- **THEN** all new observation requests are denied
- **AND** the denial reason is available for audit or user feedback

#### Scenario: Window matches a privacy rule

- **WHEN** the attached application or window title matches a configured privacy rule
- **THEN** the observation request is denied
- **AND** no raw or semantic screen content is made available to the runtime

#### Scenario: Window is eligible for observation

- **WHEN** observation is enabled, privacy mode is off, and the window does not match a privacy rule
- **THEN** the request is allowed subject to the remaining observation policy and capability checks

### Requirement: Expose observation status

The system SHALL expose whether observation is active, paused, denied, unavailable, or eligible-but-not-currently-requested, together with a non-sensitive reason suitable for display in the desktop client.

#### Scenario: User inspects observation status

- **WHEN** the user opens the desktop client status view
- **THEN** the client can show the current observation state and reason without revealing protected window content
