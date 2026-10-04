## Purpose

让 Meguri 用户把一件自己确认过的工程任务转交给本机已经安装并登录的工作 Agent（Codex 或 DeepSeek Harness），同时保证投递至多发生一次、状态永远可核实、桌宠绝不代用户审批。

## ADDED Requirements

### Requirement: Forwarding is explicitly enabled and never implicit

The desktop SHALL keep task forwarding disabled until the user explicitly enables it, and MUST NOT install, upgrade, authenticate or otherwise modify a work agent installation on its own.

#### Scenario: Forwarding has never been enabled
- **WHEN** the user has not enabled forwarding in settings
- **THEN** the task panel SHALL report no targets, no receipts and disabled executor connections, and the main process SHALL NOT spawn a Codex app-server or a Harness host

#### Scenario: User enables forwarding
- **WHEN** the user enables forwarding in settings
- **THEN** the main process SHALL install the Meguri work preset into the configured Harness home only if that exact file does not already exist, and SHALL report the resulting install state

#### Scenario: A work agent is missing
- **WHEN** neither Codex nor the DeepSeek Harness is installed
- **THEN** the companion SHALL remain fully usable for chat and existing capabilities and SHALL NOT fail startup

### Requirement: A task card is frozen before any work agent is contacted

The desktop SHALL represent a request to forward work as a durable card that records the user's exact text, the chosen executor, the verified target and an optimistic version, and preparing a card MUST NOT contact a work agent with the task text.

#### Scenario: Preparing a card
- **WHEN** the user submits text with an executor and a target
- **THEN** the desktop SHALL re-verify the target against the executor itself, persist the card, and return it without sending any task

#### Scenario: The chosen target cannot be reproduced
- **WHEN** the submitted target is not an existing user-started task of the selected executor
- **THEN** the desktop SHALL reject the card with a recoverable message and SHALL NOT persist a sendable card

#### Scenario: The card disagrees with the executor's own project
- **WHEN** the submitted project directory does not match the selected task's recorded working directory
- **THEN** the desktop SHALL reject the card

### Requirement: A confirmed card is delivered at most once

The desktop SHALL persist the dispatch claim for a card before performing the irreversible send, and a repeated, stale or replayed confirmation MUST NOT produce a second delivery.

#### Scenario: The user confirms a card
- **WHEN** the user confirms the exact version of a card that is awaiting confirmation and the executor is ready
- **THEN** the desktop SHALL record the confirmed and forwarding state first and then perform exactly one send

#### Scenario: The user confirms the same card again
- **WHEN** a card is already beyond awaiting-confirmation
- **THEN** the desktop SHALL return the recorded state and SHALL NOT send again

#### Scenario: The card changed since it was shown
- **WHEN** the confirmed version does not match the stored version
- **THEN** the desktop SHALL refuse the confirmation and SHALL NOT send

#### Scenario: The dispatch response is lost or ambiguous
- **WHEN** the executor does not confirm whether it accepted the task
- **THEN** the desktop SHALL record the card as unknown and SHALL NOT automatically resend it

#### Scenario: The desktop restarts with an in-flight card
- **WHEN** Meguri starts with a card whose claim was already persisted
- **THEN** the desktop SHALL resume read-only observation and SHALL NOT re-send the task

#### Scenario: The selected executor is not ready
- **WHEN** the user confirms a card while the selected executor is unavailable
- **THEN** the desktop SHALL refuse the confirmation before claiming the card and SHALL report why

### Requirement: Receipts are observed read-only and never fabricated

The desktop SHALL obtain a card's outcome by reading the executor's own records and MUST report unknown rather than inferring success from ambiguous evidence; a completed receipt MUST NOT be downgraded by a later ambiguous observation.

#### Scenario: Codex reports a completed turn
- **WHEN** the exact turn started for this card reports completed
- **THEN** the card SHALL become completed with the turn's assistant text, bounded in length

#### Scenario: Codex reports a failed or interrupted turn
- **WHEN** the exact turn reports failed or interrupted
- **THEN** the card SHALL NOT be reported as completed and SHALL carry an actionable detail

#### Scenario: Harness events are incomplete or out of order
- **WHEN** the Harness event page sequence is non-contiguous, the request does not bind to exactly one turn, a turn mixes two requests, or an approval is unbalanced
- **THEN** the receipt SHALL be unknown

#### Scenario: Harness has a pending native approval
- **WHEN** the Harness reports an open approval for the card's turn
- **THEN** the card SHALL report the approval state together with the allow-listed tool names and SHALL NOT report completion

#### Scenario: A later observation is ambiguous
- **WHEN** a card already holds a completed receipt and a subsequent observation returns unknown
- **THEN** the stored completed receipt SHALL be preserved

#### Scenario: Dismissing a reminder
- **WHEN** the user hides an unconfirmed card
- **THEN** the card SHALL be retired without any send, and hiding a confirmed card SHALL NOT alter its recorded delivery state or result

### Requirement: The desktop never approves on the user's behalf

The desktop MUST decline tool approvals, MUST NOT answer interactive agent questions, and MUST NOT expose a general-purpose send entry to the renderer.

#### Scenario: Codex requests a command or file approval
- **WHEN** the Codex app-server asks for an approval
- **THEN** the desktop SHALL decline it and SHALL tell the user to continue in Codex

#### Scenario: The renderer asks for a raw send
- **WHEN** the renderer attempts to reach a forwarding transport directly
- **THEN** no such bridge member SHALL exist; the renderer can only freeze a card, confirm a version it was shown, and read receipts

### Requirement: Credentials and private files stay with the work agent

The desktop MUST NOT read, store, log or return a work agent's model credentials, and MUST NOT write a Harness launcher token outside the private launch log.

#### Scenario: Reporting Harness credential state
- **WHEN** the settings read the Harness install state
- **THEN** they SHALL report only whether a DeepSeek key reference exists and SHALL NOT return its value

#### Scenario: Starting the Harness host
- **WHEN** the user opted into Meguri starting the Harness host
- **THEN** the launcher output SHALL be written to the private service log only and SHALL NOT appear in a log line, the renderer, or a stored receipt

#### Scenario: Connecting to the Harness host
- **WHEN** the desktop exchanges the Harness launcher URL for a browser session
- **THEN** it SHALL require a literal loopback HTTP origin with exactly one token parameter, SHALL NOT follow redirects, and SHALL NOT send the launcher URL to a configurable host

### Requirement: Only local project metadata is exposed

Target and project discovery SHALL return bounded metadata for local, user-started work only, and MUST NOT read project contents or accept remote or non-local locations.

#### Scenario: Listing Codex targets
- **WHEN** the desktop lists forward targets
- **THEN** it SHALL include only user-started, non-ephemeral, non-sub-agent threads whose working directory is an absolute local path, deduplicated by identity, without exposing any file contents

#### Scenario: A task directory is offered as a project
- **WHEN** a target's working directory is not already a known project
- **THEN** the desktop SHALL offer it as a project entry derived from that directory path only
