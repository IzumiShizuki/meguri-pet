## Context

The existing desktop resource picker provides an opaque resource ID plus a
local path that the Core revalidates through `LocalResourcePathPolicy`. Visual
attachments already have a send-time resolver and the capability runtime has
an approval model for writes, but there is no bounded document parser, no
model-facing edit contract, and no desktop confirmation UI.

## Goals / Non-Goals

**Goals:**

- Preserve the explicit-selection boundary for every read and write.
- Let compatible model routes receive bounded TXT/Markdown/CSV/JSON/YAML/DOCX
  content and produce an exact, machine-checkable edit proposal.
- Make writes user-confirmed, digest-bound, recoverable, and visible in the
  desktop UI.

**Non-Goals:**

- Arbitrary filesystem browsing, shell execution, or silent model edits.
- Editing legacy binary `.doc`, spreadsheets, PDFs, or encrypted/protected
  Office files in the first release.
- Large-format document reflow or authoring; DOCX edits are exact text
  replacements so unaffected formatting remains intact.

## Decisions

### Reuse an opaque, revalidated attachment rather than accept paths in tools

The parser will only accept `local_file_reference` attachments from Everything
whose `content_access` is `document_read`. It maps the stable `reference_id` to
the revalidated path and a SHA-256 digest for that one turn. This keeps a model
from naming a different path. An alternative path argument was rejected because
it would turn a content request into filesystem authority.

### Keep all model output declarative until the user confirms

The prompt contract will ask for a fenced `meguri-document-edit` JSON object.
Plain-text formats propose an exact full replacement; DOCX proposes a bounded
list of exact `find`/`replace` operations. The Core verifies target reference,
digest, size, and operation limits, stores a short-lived opaque preview token,
and emits it with the completed turn. An immediate write tool was rejected
because chat output cannot prove user intent to overwrite a local file.

### Parse document formats without adding a runtime dependency

UTF text formats are decoded only with a safe supported charset and byte cap.
DOCX is treated as a constrained ZIP package: the Core reads only a bounded
`word/document.xml` entry using hardened XML parsing, and rewrites only that
entry after a confirmed exact replacement. This avoids expanding dependency
surface while preserving unaffected Office package entries. Apache POI was not
selected because it is not currently a project dependency and requires a user
choice before adding a large runtime library.

### Confirm through the local Core and use atomic replacement

The desktop invokes a local Core endpoint with the opaque preview token. The
Core rechecks policy and digest, copies the original as a same-directory,
timestamped `.meguri-backup` file, writes a temporary sibling, then atomically
replaces the original when the filesystem supports it. The preview can be used
once and expires. Direct renderer writes were rejected because Electron UI code
would duplicate path policy and cannot guarantee atomic recovery.

## Risks / Trade-offs

- [Model returns malformed proposal JSON] → Ignore it as normal reply text and
  never expose a confirm button.
- [Document changes while user reviews it] → Bind preview to SHA-256 and
  reject stale confirmations.
- [DOCX has text split across complex runs] → Limit first release to exact
  paragraph-level matching and explain when a replacement cannot be applied.
- [Same-folder backup cannot be written] → Abort before changing the source and
  report the filesystem error.
- [Office layout changes after text replacement] → Preserve all other OOXML;
  prompt users to review the backup in Word for layout-critical documents.

## Migration Plan

1. Deploy the Core parser/proposal store and local confirmation endpoint while
   retaining existing image/PDF attachment behavior.
2. Extend AIRI `@` selection to mark supported documents as `document_read`.
3. Render proposal bubbles/buttons only when the Core emits a valid token.
4. Roll back by disabling document-read attachment exposure; stored preview
   tokens are in-memory and expire, while user backups remain recoverable.
