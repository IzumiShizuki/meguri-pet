## Purpose

Ensure users can start or reopen conversations and resume sending messages while prior asynchronous operations finish, without mixing conversation state or losing persisted completed dialogue.

## ADDED Requirements

### Requirement: A new conversation is immediately usable
The desktop SHALL clear the prior turn's transient sending state synchronously and isolate all subsequent callbacks by conversation identity.

#### Scenario: New conversation during an unfinished request
- **WHEN** the user creates a new conversation while the previous request is pending
- **THEN** the user can immediately send a new message and a delayed completion or cleanup from the old conversation cannot alter the new conversation

#### Scenario: Completed dialogue is reopened
- **WHEN** the desktop reloads with persisted completed dialogue
- **THEN** it restores that dialogue and permits the next message without replaying prior side effects

### Requirement: Recovery retains session boundaries
The runtime SHALL retain completed conversation context and ordered events across supported recovery while preventing one conversation's history from entering another.

#### Scenario: Multi-turn recovery and new identity
- **WHEN** a completed session is recovered and an independent session is created
- **THEN** the recovered session retains its own prior context while the independent session starts without that context

#### Scenario: Interrupted request is reopened
- **WHEN** the desktop reloads after a request stopped before a terminal response
- **THEN** it presents a recoverable interruption and permits another request without remaining busy
