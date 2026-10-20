## Purpose

Allow selected AstrBot personas to use the Meguri runtime while preserving AstrBot's native chat behavior for other personas on the same bot.

## ADDED Requirements

### Requirement: Optional exact persona routing
The gateway SHALL offer a disabled-by-default persona-routing switch and an exact, case-sensitive list of Meguri persona IDs. When enabled, ordinary messages for personas outside that list MUST remain in AstrBot's native pipeline without a Core request, a gateway reply, LLM suppression, or event termination. An empty list SHALL select no personas for automatic Meguri chat.

#### Scenario: Selected Meguri persona
- **WHEN** persona routing is enabled, the effective persona ID is listed, and existing automatic routing and authorization permit the message
- **THEN** the gateway invokes Core once and suppresses the native LLM as before

#### Scenario: Other persona
- **WHEN** persona routing is enabled and the effective persona ID is not listed
- **THEN** the ordinary message remains with AstrBot, including its native tools and context, and no Meguri reminder draft is created

#### Scenario: Empty allowlist
- **WHEN** persona routing is enabled with an empty persona list
- **THEN** all ordinary messages remain with AstrBot

#### Scenario: Legacy configuration
- **WHEN** the persona-routing switch is absent or disabled
- **THEN** existing gateway routing remains unchanged and no persona lookup is required

### Requirement: Effective persona and session isolation
Routing MUST reflect the effective persona for the message's own AstrBot session, including conversation selection, configured default and forced session rules. Persona switches MUST affect the next message without a plugin restart. Lookup failures, missing session identity and the explicit no-persona selection MUST leave ordinary messages with AstrBot. Resolving routing MUST NOT create or switch conversations, copy histories, or expose message bodies or credentials in diagnostics.

#### Scenario: Default persona without conversation
- **WHEN** a chat has no existing conversation and its configured default persona is listed
- **THEN** ordinary chat uses Core without creating an AstrBot conversation

#### Scenario: Forced session persona
- **WHEN** a session rule overrides a conversation's persona
- **THEN** routing follows the forced effective persona

#### Scenario: Persona switch across private and group sessions
- **WHEN** one private or group session switches persona while another session keeps its selection
- **THEN** the next message in each session follows its own current persona

#### Scenario: Lookup unavailable or no persona
- **WHEN** the persona API is unavailable, lookup fails, the session identity is missing, or no persona is selected
- **THEN** ordinary chat remains with AstrBot without a Core call or gateway error reply

### Requirement: Preserve explicit commands and routing boundaries
Explicit `/meguri` commands MUST bypass persona selection while retaining sender authorization and group restrictions. Existing plugin-owned commands MUST retain priority. Persona selection MUST NOT enable automatic routing when its private/group switch is disabled.

#### Scenario: Explicit Core request from native persona
- **WHEN** a permitted user on a native persona sends an explicit `/meguri` command
- **THEN** the gateway handles the command without needing persona resolution

#### Scenario: Existing permissions and automatic switches
- **WHEN** a selected Meguri persona sends a message blocked by sender/group rules or disabled automatic routing
- **THEN** persona selection does not override those rules

#### Scenario: Plugin command
- **WHEN** another enabled plugin owns the message
- **THEN** that plugin retains ownership without a persona lookup or Core call
