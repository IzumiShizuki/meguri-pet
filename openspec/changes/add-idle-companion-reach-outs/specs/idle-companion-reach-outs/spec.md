## Purpose

Provide occasional grounded conversation starters while the desktop is running, preserving user control, conversational continuity and quiet time without manufacturing user activity.

## ADDED Requirements

### Requirement: Bounded idle scheduling
The desktop SHALL attempt reach-outs only after a completed real exchange, with current AI consent and initiative enabled. It MUST remain quiet during 22:00–08:00 local time, focus mode, an active turn, speech, editing, or a hidden stage. Low, normal and high initiative SHALL wait at least 120, 45 and 15 idle minutes respectively, respect existing notice cooldowns, limit delivery to three per local day, and allow only one unanswered reach-out after an exchange. Restarting the stage SHALL restart the idle waiting period.

#### Scenario: Eligible idle conversation
- **WHEN** an available, consented desktop has been idle for the selected interval after a completed exchange
- **THEN** it requests at most one candidate and presents eligible text through the existing notice delivery path

#### Scenario: User activity during generation
- **WHEN** the user starts editing or a turn, switches session, withdraws consent, disables initiative, or enters quiet/focus mode before delivery
- **THEN** the pending candidate is cancelled or discarded without a visible message or assistant context write

#### Scenario: Unanswered reach-out
- **WHEN** a reach-out was displayed and the user has not completed another exchange
- **THEN** no additional reach-out is generated, including after stage reload

### Requirement: Separate grounded generation
Core SHALL generate a short optional message using the existing persona and bounded recent scoped conversation. It MUST expose no tools, create no user turn, perform no automatic formal memory writes, and support declining to speak. Missing context, unsupported providers, failures and stale candidates SHALL leave the desktop silent with bounded retry behavior.

#### Scenario: Context and identity isolation
- **WHEN** an authenticated desktop requests a candidate
- **THEN** Core reads only the verified user, desktop client and session context and rejects identity mismatches

#### Scenario: Unavailable generation
- **WHEN** the provider is unavailable or returns an invalid/empty candidate
- **THEN** the desktop remains usable and silent and does not fall back to a fabricated user turn

### Requirement: Delivery acknowledgement and continuity
Candidates SHALL be deduplicated by completed exchange. Only acknowledged displayed messages SHALL enter the session as assistant context; acknowledgements MUST be idempotent and MUST NOT attach stale content after a new branch or exchange.

#### Scenario: Candidate never displayed
- **WHEN** a generated candidate is cancelled or expires before delivery
- **THEN** the user conversation and formal memory remain unchanged

#### Scenario: User replies to a displayed starter
- **WHEN** delivery is acknowledged and the user continues chatting
- **THEN** the assistant can see its own starter in recent context, with no synthetic user message

### Requirement: Existing presentation controls
Reach-outs SHALL honor current speech mute, surface playback, focus and initiative controls. Turning initiative off SHALL cancel pending reach-outs while preserving explicitly requested reminders and follow-ups.

#### Scenario: Muted or disabled initiative
- **WHEN** speech is muted
- **THEN** eligible reach-outs remain text-only
- **WHEN** initiative is off
- **THEN** idle reach-outs are not generated or delivered
