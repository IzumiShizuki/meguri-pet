## Purpose

Provide explicit, bounded reads of public web documents for external Skills while preserving source links and the existing permission and untrusted-data boundaries.

## ADDED Requirements

### Requirement: Fetch public documents without credentials
The system SHALL expose an opt-in web_fetch read tool requiring existing read scope and network permission. It MUST accept a URL, text extract mode and bounded character limit, and return source URL, title, text, links and truncation metadata as untrusted data. It MUST NOT send cookies, authorization, URL credentials or arbitrary user-supplied headers.

#### Scenario: Select a search result
- **WHEN** an authorized invocation reads HTML containing an HTTP(S) anchor
- **THEN** the bounded result contains the link text and absolute target URL without fetching the target automatically

#### Scenario: Disabled or unauthorized
- **WHEN** the feature is disabled or a turn lacks scope or network permission
- **THEN** the tool is unavailable or rejected before any network access

### Requirement: Validate the actual destination for every hop
The system MUST reject non-public, loopback, private, link-local, shared, benchmark, metadata and unsafe transition addresses, credential URLs and nonstandard ports. It MUST connect only to a validated DNS snapshot, revalidate every redirect, reject mixed public/private answers and HTTPS downgrade, and bound redirect count.

#### Scenario: DNS changes after initial response
- **WHEN** a same-host redirect is followed and its new DNS response is private
- **THEN** no request is made to that destination

#### Scenario: Address changes between validation and connect
- **WHEN** DNS changes after a public address snapshot is validated
- **THEN** the connection uses only the approved snapshot and cannot perform another uncontrolled resolution

### Requirement: Bound and isolate response processing
The system MUST bound bytes, characters, links, headers, time and redirects, honor cancellation, and reject unsupported content or encoding. HTML script/style content MUST be removed; visible page instructions MUST remain external document data and never become authority, memory or tool permissions.

#### Scenario: Oversized or stalled document
- **WHEN** a response exceeds the configured fixed byte bound or operation deadline
- **THEN** processing stops with failure and a later independent request can proceed

#### Scenario: Injection text in a page
- **WHEN** a page requests changing system instructions or calling a write tool
- **THEN** the result is labeled untrusted external data and grants no additional capability

#### Scenario: Long source or link addresses in a ReAct observation
- **WHEN** a fetched document has long source or link URLs, or many ordinary links, in any result field order
- **THEN** independent budgets preserve a bounded document-text section and source/link addresses are included whole or explicitly omitted, never misleadingly truncated
