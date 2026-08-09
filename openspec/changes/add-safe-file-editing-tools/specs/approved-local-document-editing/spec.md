## Purpose

Allow the desktop pet to read and prepare safe changes for a document the user
explicitly selected, while preventing unapproved local-file reads and silent
or irreversible writes.

## ADDED Requirements

### Requirement: Explicit approved document input
The system SHALL accept an approved Everything local-file reference for a TXT,
Markdown, CSV, JSON, YAML, or DOCX document only when the turn marks it for
document reading. It MUST revalidate the local file immediately before reading,
limit the parsed content, and expose an opaque reference identifier rather than
granting filesystem access to the model.

#### Scenario: User selects a text document
- **WHEN** the user selects a safe TXT or Markdown file through `@` and sends a document request
- **THEN** the model receives bounded document text and an opaque identifier for that selected file only

#### Scenario: User selects a Word document
- **WHEN** the user selects a safe DOCX file through `@` and sends a document request
- **THEN** the system extracts bounded document text for review while retaining the original DOCX as the edit target

#### Scenario: File is unsafe, unsupported, stale, or oversized
- **WHEN** a document reference no longer resolves safely or violates its supported type or size limit
- **THEN** the turn SHALL fail with a user-visible explanation and SHALL not read or write that file

### Requirement: Previewed document edit proposal
The system SHALL recognize a structured document-edit proposal only when it
targets a document approved for that same turn and matches the read version's
content digest. The user-facing result MUST include a change summary and a
preview token; it MUST not alter the file at proposal time.

#### Scenario: Model proposes a valid edit
- **WHEN** a response includes a valid structured replacement or DOCX find-and-replace plan for an approved document
- **THEN** the system SHALL present a preview with a confirm action and leave the original file unchanged

#### Scenario: Model proposal targets an unapproved or changed document
- **WHEN** a proposal has an unknown reference identifier or a mismatched digest
- **THEN** the system SHALL discard the proposal and SHALL not present a write action

### Requirement: Confirmed and recoverable local write
The system SHALL apply a proposed document edit only after a desktop user
explicitly confirms its preview token. Immediately before writing, it MUST
revalidate the selected path and content digest, create a same-folder backup,
and report either the changed file or a clear failure without partial writes.

#### Scenario: User confirms a current preview
- **WHEN** the user confirms an unexpired preview whose file still matches the reviewed digest
- **THEN** the system SHALL create a recoverable backup and atomically replace the selected document with the approved edit

#### Scenario: File changes before confirmation
- **WHEN** the document digest differs at confirmation time
- **THEN** the system SHALL refuse to apply the preview and ask the user to review the current document again

#### Scenario: User declines a preview
- **WHEN** the user dismisses a proposed document edit
- **THEN** the system SHALL not modify the selected file

### Requirement: Document-editing prompt scope
The system SHALL expose document-reading and edit-proposal instructions only
for a turn with an explicitly approved document. These instructions MUST state
that the model cannot access other local files and must return a structured
proposal instead of claiming that it has already written the file.

#### Scenario: Ordinary conversation without a document
- **WHEN** a conversation has no approved document attachment
- **THEN** ordinary planning, agent activation, and filesystem permissions SHALL remain unchanged
