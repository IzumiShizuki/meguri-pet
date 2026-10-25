## Purpose

Allow a running desktop companion to start optional conversations and read bounded visible screen text only while its owner has recently used the mouse or keyboard, remaining quiet when an unattended PC stays awake.

## ADDED Requirements

### Requirement: Trusted ten-minute presence gate
Autonomous reach-outs, unsolicited news delivery and automatic screen observation MUST require a successful native activity sample showing less than 600 seconds since the last mouse or keyboard input. Locked, suspended, unknown or expired samples SHALL suppress work. Presence SHALL be rechecked before observation and delivery, and stale in-flight results MUST be discarded. Physical presence MUST remain independent from the conversation-idle interval. Explicitly requested reminders and confirmed commitment follow-ups SHALL retain their existing behavior.

#### Scenario: Owner leaves an awake PC
- **WHEN** native input age reaches 600 seconds
- **THEN** no automatic capture, generation or visible reach-out starts, even if the PC is not asleep

#### Scenario: Owner works in another application
- **WHEN** recent native input exists and conversation-idle/cooldown eligibility is satisfied
- **THEN** the companion can attempt one optional reach-out without resetting the conversation timer on that unrelated input

#### Scenario: Activity expires during generation
- **WHEN** the desktop locks, suspends or passes the ten-minute boundary before delivery
- **THEN** the generated text is discarded and no assistant context acknowledgement is written

### Requirement: Explicit bounded screen context
Automatic screen observation SHALL be controlled by a persisted owner switch, default off for new installations, and require existing AI consent and companion eligibility. Observation MUST read only the foreground window through local OCR, avoid credential/private windows, and keep image bytes off disk and clipboard. Only bounded recognized text MAY be sent to the configured model as untrusted data. It MUST NOT expose tools, create a synthetic user turn, or write formal memory automatically.

#### Scenario: Observation is enabled and eligible
- **WHEN** the owner has enabled screen observation and recent activity/presentation/consent gates pass
- **THEN** a bounded local OCR observation may inform the existing optional conversation starter and delivery limits

#### Scenario: Observation is disabled or unavailable
- **WHEN** the switch is off, the foreground window is excluded, OCR fails or no useful text is recognized
- **THEN** no image/context is sent and ordinary eligible conversation starters remain available

### Requirement: Reviewable controls and coordinated release
Settings SHALL explain the ten-minute gate, separate chat cooldown, screen-text sharing and how to disable observation. The main branch implementation MUST be merged into the requested branch before deploying the matching native host, renderer, gateway and Core. Deployment SHALL preserve existing configuration/data and provide rollback/version evidence.

#### Scenario: Inspect deployed branch
- **WHEN** the user opens companion settings after deployment
- **THEN** the current activity/observation state and explicit screen switch are visible, and the running version matches the merged branch
