## Purpose

Enable users to create reviewed TimePrism reminders from typed or spoken Chinese requests, with explicit timezone semantics, safe uncertain-write handling, and accurate local delivery and action support boundaries.

## ADDED Requirements

### Requirement: Shared intent parsing and clarification
Typed requests and editable speech transcripts SHALL use the same bounded parser. It SHALL extract a title, behavior type and minute-precision Asia/Shanghai time, resolving relative dates against the parse instant. Missing dates, ambiguous bare hours, invalid/past times, multiple schedules and unsupported zones SHALL require clarification rather than guessing. Recurring schedules SHALL be rejected as unsupported. Non-scheduling chat SHALL retain its ordinary flow.

#### Scenario: Tomorrow morning meeting
- **WHEN** the user submits “明天上午 9 点提醒我开会” by text or voice draft
- **THEN** the preview shows title “开会”, tomorrow at 09:00, reminder type and Asia/Shanghai (UTC+08:00), identically for both inputs

#### Scenario: Ambiguous time
- **WHEN** the user submits “明天 3 点提醒我开会” or “明天提醒我开会”
- **THEN** Meguri asks for a precise time and allows the user to provide it before a new review

#### Scenario: Calendar and timezone boundaries
- **WHEN** a relative date crosses a month/year boundary, or a device uses a DST-observing timezone
- **THEN** the selected Asia/Shanghai date/time and trigger instant remain correct and independent of the device timezone; other requested zones are not silently converted

### Requirement: Explicit review and capability limits
Meguri SHALL present title, date/time, timezone, behavior and local-only availability before requiring an explicit confirm control. The only allowed capability SHALL be reminder creation using the connected user's account. Scheduled order checks, arbitrary shell/computer operations, external message sends and offline-trigger requests SHALL be rejected with an explanation; they MUST NOT reach an executor or be reported as arranged.

#### Scenario: Preview does not write
- **WHEN** the user submits a complete reminder or edits a voice transcript
- **THEN** no Todo is written until the user confirms the reviewed values

#### Scenario: Cancel before request dispatch
- **WHEN** the user cancels before a request is sent
- **THEN** the review closes and no Todo write is submitted

#### Scenario: Cancel while a request is in flight
- **WHEN** the user cancels after dispatch while the server call is pending
- **THEN** Meguri stops the current review flow after the in-flight call returns, refreshes the unresolved request list, and preserves the original request identity for later reconciliation
- **AND** if the server confirms creation, Meguri reports that result instead of implying the Todo was cancelled

#### Scenario: Dismiss an uncertain request review
- **WHEN** the user cancels after a request has entered uncertain state
- **THEN** Meguri refreshes the unresolved request list, closes the active review flow, and keeps the original request identity available for later reconciliation

#### Scenario: Unsupported action
- **WHEN** the user submits “明天下午 3 点帮我检查订单”
- **THEN** Meguri says scheduled order checking is unavailable and does not create a reminder or action in its place

#### Scenario: Offline guarantee requested
- **WHEN** the user requests a reminder even after shutdown or Meguri exit
- **THEN** Meguri explains that a server scheduler/worker is required and does not promise or create an always-on schedule

### Requirement: Server-confirmed creation and safe retry
Meguri SHALL report creation only after an authenticated server response or reconciled read confirms a positive Todo ID and the reviewed title/time, enabled minute-precision zero-offset reminder. A persistent request identity SHALL be bound to account, origin and reviewed content. Concurrent confirmations and retries SHALL not duplicate a write. Failures SHALL preserve the review/draft and permit safe retry. A timeout, network error, invalid response or server failure after dispatch SHALL enter uncertain state; retry SHALL reconcile the identity without issuing another POST if absence cannot prove non-creation. Unresolved requests SHALL survive restart.

#### Scenario: Valid saved reminder
- **WHEN** the server returns the exact requested schedule including enabled `0 MINUTE` reminder
- **THEN** Meguri shows the saved Todo and can feed it to the local reminder runtime

#### Scenario: Unsupported production fields
- **WHEN** the server rejects or drops minute reminder fields
- **THEN** Meguri shows an error or unverified-record warning, preserves inputs, and never reports a confirmed reminder

#### Scenario: Response lost after insert
- **WHEN** POST inserts a record but its response is lost and the user retries or restarts
- **THEN** Meguri recovers the matching request record by reading the server and does not repeat POST

#### Scenario: Definitive rejection
- **WHEN** the server returns a definitive validation/authorization rejection
- **THEN** Meguri permits a corrected or same-request retry without claiming success

### Requirement: Server-acknowledged edit and cancellation
The Todo panel SHALL let the user review and confirm a title/time change or cancellation of a specific existing Todo. It SHALL preserve unrelated Todo fields, update local delivery only after server acknowledgement, and suppress a confirmed cancelled occurrence. An ambiguous natural-language edit/cancel SHALL direct the user to select a Todo rather than guessing which record to mutate.

#### Scenario: Change reminder time
- **WHEN** the user selects an existing Todo, reviews a future time and confirms its edit
- **THEN** the server-confirmed new schedule replaces the old local snapshot and the old occurrence does not trigger

#### Scenario: Cancel reminder
- **WHEN** the server confirms disabling the selected Todo's reminder
- **THEN** its future reminder does not fire and the Todo remains available

### Requirement: Honest local trigger and delivery status
The desktop SHALL trigger active reminders within its 30-second scan interval while running with a valid snapshot, with persisted occurrence deduplication across restart. It SHALL refresh approximately every 75 seconds and suppress stale snapshots after failed synchronization. On restart it SHALL deliver at most once within a twelve-minute late window. It MUST NOT claim to trigger while stopped, guarantee delivery through network partition, or conflate Core receipt with QQ delivery. Local speech failure SHALL not remove the reminder card.

#### Scenario: Duplicate scan and restart
- **WHEN** repeated scans or a restart observe the same due occurrence
- **THEN** it is locally emitted at most once when the deduplication store is writable

#### Scenario: Second desktop instance
- **WHEN** another desktop instance opens the same profile after the Windows elevation handoff
- **THEN** it focuses the existing owner and does not start a second reminder or write runtime

#### Scenario: Offline desktop
- **WHEN** Meguri stops before due time
- **THEN** no local trigger occurs; restart within twelve minutes may deliver a missed reminder once, while older reminders remain in the Todo list

#### Scenario: Network failure or execution failure
- **WHEN** synchronization, notification, speech or Core upload fails
- **THEN** failure remains distinguishable from creation and QQ delivery; a failed sync prevents stale cancelled/changed Todos from being treated as current

### Requirement: Separate verification evidence
General paths SHALL be tested with mocks or a test server. Production results SHALL be recorded separately with exact probe limits. Any live write test MUST use an identifiable cleanup-safe record and remove it afterward; no automated test SHALL notify another person. Unrelated PixPin and TTFT tasks MUST retain their actual incomplete status.

#### Scenario: Read-only production probe
- **WHEN** production endpoints require authentication or expose no scheduling capability metadata
- **THEN** verification reports the observed responses and minute/worker support as unverified, without promoting mock success to production end-to-end success
