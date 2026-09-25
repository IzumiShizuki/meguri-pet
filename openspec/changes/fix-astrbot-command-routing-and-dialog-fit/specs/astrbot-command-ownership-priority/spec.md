## Purpose

Ensure an inbound AstrBot message reaches the plugin that owns it, so a command
handled by a dedicated plugin is never intercepted and answered by the Meguri
gateway as ordinary chat.

## ADDED Requirements

### Requirement: Owned commands are released before Meguri routing

The system SHALL decide command ownership before routing a message, and SHALL
release a message whose text names a command belonging to another plugin without
blocking the default LLM or stopping event propagation.

#### Scenario: A message names another plugin's command

- **WHEN** a private or group message's text equals, or begins with, a command name
  or alias belonging to a plugin other than the gateway
- **THEN** the gateway neither answers the message nor stops the event, so the
  owning plugin handles it

#### Scenario: A message carries command arguments

- **WHEN** the message is a command name followed by a space and arguments
- **THEN** the message is still released to the owning plugin

#### Scenario: The message is an ordinary chat message

- **WHEN** the message text does not name any other plugin's command
- **THEN** the configured routing policy decides as before and ordinary chat still
  reaches Meguri

### Requirement: Explicit Meguri invocation always wins

The system MUST keep an explicit Meguri command with the gateway even when its
text could match another plugin's command.

#### Scenario: A Meguri command mentions a released command

- **WHEN** the message starts with the configured Meguri prefix
- **THEN** the message is routed to Meguri and not released, regardless of the
  command text that follows

### Requirement: Command discovery degrades safely

The system SHALL derive other plugins' command names from the host's registered
command handlers when available, SHALL cache the derived set, and SHALL fall back
to the configured release list when that introspection is unavailable.

#### Scenario: Host introspection is unavailable

- **WHEN** the host's command registry cannot be read
- **THEN** the configured release list remains authoritative and no message is
  released or routed because of the failure

#### Scenario: A plugin registers commands conventionally

- **WHEN** another plugin registers a command through the host's command filter
- **THEN** that command name and its aliases participate in the release decision
  without any gateway code change

### Requirement: Released messages keep plugin priorities

The system SHALL NOT suppress the host's default LLM for a message it releases,
so a released message behaves exactly as if the gateway had not run.

#### Scenario: A released command's plugin misses the message

- **WHEN** the gateway releases a message that the owning plugin does not answer
- **THEN** the host's normal LLM path remains available rather than being blocked
  by the gateway

### Requirement: The wife plugin owns its pinyin aliases

The wife plugin SHALL treat `jrlp` and `jrlb` as "today's wife": read today's wife
when one is recorded and draw one otherwise. Matching SHALL be case-insensitive for
ASCII aliases, private chats SHALL use a per-sender scope, and a message the plugin
owns SHALL suppress the default LLM.

#### Scenario: jrlp with no wife recorded today

- **WHEN** a user sends `jrlp` and today's wife is not recorded
- **THEN** the plugin draws a wife for that user

#### Scenario: jrlp with today's wife already recorded

- **WHEN** a user sends `jrlp` and today's wife is recorded
- **THEN** the plugin reports the recorded wife instead of drawing a new one

#### Scenario: Alias sent in upper case

- **WHEN** a user sends `JRLP`
- **THEN** the plugin matches the alias and answers, and the default LLM is
  suppressed for that message

#### Scenario: Alias sent in a private chat

- **WHEN** a user sends `jrlp` in a private chat with no group context
- **THEN** the plugin uses a scope derived from that sender instead of failing
