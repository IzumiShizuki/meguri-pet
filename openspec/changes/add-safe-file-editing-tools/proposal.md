## Why

Users can currently attach images and PDFs, but cannot ask the desktop pet to
read or edit a selected text document. Passing a file path to a model is not
enough: a useful editing flow needs bounded parsing, an inspectable change
preview, and an explicit user-controlled write.

## What Changes

- Add an approved-local-document capability for reading user-selected TXT,
  Markdown, DOCX, and CSV files into a bounded tool result.
- Add a document-edit proposal tool that produces a textual replacement patch
  for an already-approved file, rather than writing immediately.
- Add a confirmation endpoint and desktop UI flow that validates the same
  selected file again, creates a recoverable backup, applies an approved patch,
  and reports the resulting file as a desktop artifact.
- Keep unsupported, binary, oversized, unsafe, or stale file references
  rejected with a visible error. No tool receives access to arbitrary paths.

## Capabilities

### New Capabilities

- `approved-local-document-editing`: Safely inspect and edit user-approved
  local text documents through a preview-and-confirm workflow.

### Modified Capabilities

- None.

## Impact

- Java Core local-resource policy, capability runtime, ReAct tool execution,
  and turn/artifact events.
- AIRI resource selection, conversation UI, Electron IPC, and adapter event
  rendering.
- No new runtime package dependency is required for TXT, Markdown, or CSV.
  DOCX handling will use an existing local helper only after it is verified as
  part of implementation.
