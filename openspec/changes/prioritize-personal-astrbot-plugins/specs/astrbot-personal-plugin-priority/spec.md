## Purpose

Ensure that existing AstrBot plugins remain usable in private conversations while Meguri provides ordinary character chat, by giving matching plugin commands and supported automatic triggers ownership before Core execution.

## ADDED Requirements

### Requirement: Matched plugin commands and regex triggers take priority
The gateway SHALL yield messages matched by another enabled plugin's command, command-group leaf, or regex handler before invoking Core. It MUST preserve AstrBot's per-event platform, session, permission and message-type filtering, and MUST suppress default LLM chat for a released plugin invocation without stopping plugin dispatch.

#### Scenario: Private video command
- **WHEN** a private message matches an enabled Bilibili-search command or alias
- **THEN** that plugin handles the message, with no Meguri Core turn or default LLM response

#### Scenario: Private Bilibili URL or BV identifier
- **WHEN** a private message matches an enabled plugin's Bilibili regex handler
- **THEN** the gateway yields it before Core execution

#### Scenario: Handler excluded from the current session
- **WHEN** a globally installed command handler is excluded by AstrBot's current session or platform rules
- **THEN** its presence in the global registry alone does not claim that message

### Requirement: Supported plain-text and automatic plugin triggers remain usable
The gateway SHALL yield configured plain-text aliases, supported media-parser links and Bilibili cards, image-search triggers and active image-search follow-ups, and loaded meme-template keywords. It MUST release only enabled handlers present in the current event for automatic triggers and MUST preserve the owning plugin's configuration and allowlist checks.

#### Scenario: Media parser accepts a link
- **WHEN** the active media parser accepts a message or share-card link under its existing configuration
- **THEN** the gateway yields to the media parser without executing downloads or network requests itself

#### Scenario: Image-search follow-up
- **WHEN** an enabled image-search handler is waiting for a non-expired follow-up from the sender
- **THEN** the sender's next reply reaches that plugin instead of Core

#### Scenario: Passive listeners and ordinary chat
- **WHEN** a message is ordinary chat and only general message listeners match
- **THEN** the gateway retains normal Core routing

### Requirement: Explicit Meguri commands retain ownership
The gateway MUST retain explicit `/meguri` invocations, including messages whose wake prefix AstrBot already removed. Configured release aliases SHALL match complete commands with whitespace-delimited arguments, optionally prefixed by `/`, case-insensitively.

#### Scenario: Explicit chat containing a plugin trigger
- **WHEN** the sender uses `/meguri chat` followed by a Bilibili URL or plugin command
- **THEN** the request stays with Meguri

#### Scenario: Prefix and whitespace
- **WHEN** the sender uses `/jrlp`, `JRLP`, or a configured alias followed by a tab or newline and arguments
- **THEN** the alias is released, but a longer word sharing the alias prefix is not

#### Scenario: AstrBot internals unavailable
- **WHEN** per-event handlers and registry discovery are unavailable
- **THEN** configured aliases still work and unrelated chat remains routed normally

### Requirement: Installed plugin catalogue is accurate about support limits
The project SHALL provide a Chinese catalogue of installed plugin commands, aliases and automatic triggers, distinguishing private-compatible, group-only, platform-dependent, administrator-only and background/LLM-tool functionality. Source-level support MUST be distinguished from successful end-to-end execution.

#### Scenario: User looks up Bilibili functions
- **WHEN** the user reads the catalogue
- **THEN** they can find video/BV/link recognition, search and subscription commands, with configuration and permission limits

#### Scenario: Group administration or LLM-only plugin
- **WHEN** the user reads a group-only function or an AstrBot LLM-tool integration
- **THEN** the catalogue does not claim it becomes a private Core capability through routing alone
