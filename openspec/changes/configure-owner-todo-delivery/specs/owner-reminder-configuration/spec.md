## Purpose

Allow the verified owner to receive TimePrism reminders with a correctly configured private delivery route while preserving existing ordinary conversation behavior.

## ADDED Requirements

### Requirement: Reminder access uses independent privileged authentication

Reminder uploads and feeds SHALL require a configured positive owner, valid bearer token, tenant, client, user and session even when global chat authentication is disabled. Uploads MUST use the configured Shizuki owner and desktop reminder session; feeds MUST use the AstrBot reminder-poller identity.

#### Scenario: Valid legacy-mode access
- **WHEN** global chat authentication is disabled and the exact privileged desktop or poller identity accesses its reminder route
- **THEN** the request succeeds without changing the ordinary chat policy

#### Scenario: Unauthorized access
- **WHEN** a caller supplies a missing or wrong token, tenant, owner, client or session
- **THEN** access is denied and reminder data is not changed or disclosed

#### Scenario: Missing privileged configuration
- **WHEN** no positive owner or shared credential is configured
- **THEN** reminder access reports an unavailable configuration

### Requirement: Verified recipient configuration is reversible

The current owner's reminder recipient MUST be taken from the previously verified enabled private adapter binding. Configuration SHALL preserve all unrelated fields, retain a rollback copy, and expose no credentials or raw sender identifiers in public reports.

#### Scenario: Existing owner is configured
- **WHEN** owner, operator and adapter match existing verified evidence
- **THEN** the private target and reminder switch are configured while unrelated plugin settings are retained

#### Scenario: Binding cannot be verified
- **WHEN** the existing binding does not match the current owner or enabled adapter
- **THEN** activation stops without adding guessed recipients

### Requirement: Verification distinguishes configuration from receipt

Verification SHALL use read-only production probes and isolated reminder collectors and SHALL distinguish an enabled reminder route from a verified real QQ receipt. Documentation MUST state that current reminder production depends on the logged-in desktop process and that multiple QQ/WeChat bindings remain a proposed design.

#### Scenario: Acceptance without external test messages
- **WHEN** configuration and protocol verification finish
- **THEN** no test message is sent to a real recipient and the report states which boundaries were actually checked

### Requirement: Advanced connection configuration is optional in daily use

The configuration page SHALL place service addresses, credential references and transport/storage tuning in a collapsed advanced group while leaving ordinary behavior and reminder controls accessible. Existing flat configuration MUST retain its effective values after migration; later grouped edits MUST take effect, including false, zero and empty values where supported.

#### Scenario: Existing configuration is migrated
- **WHEN** a plugin using legacy flat settings loads the grouped schema
- **THEN** its effective connection settings are preserved and migration is persisted only once

#### Scenario: Advanced values are edited later
- **WHEN** a migrated user changes a grouped value
- **THEN** the grouped value takes precedence over the hidden legacy value
