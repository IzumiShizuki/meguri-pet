## Context

See `proposal.md` for motivation and `specs/desktop-file-and-screenshot-ingestion/spec.md` for observable behavior. The Java Core already resolves `local_file_reference` attachments marked `multimodal_read` or `document_read`, and the loopback gateway already keeps such sessions on the local Core. The native `desktop-meguri` renderer currently creates only `not_read` Everything references and has no drag/drop or clipboard-image path. Modern Electron also removes the old renderer `File.path`, so a sandboxed preload bridge is required to recover a dropped file's native path safely.

## Goals / Non-Goals

**Goals:**

- Reuse the established Core attachment wire format and its send-time safety validation.
- Make dropped local files and pasted screenshots visibly readable before send.
- Support Snipaste through the Windows clipboard contract without coupling Meguri to Snipaste internals.
- Keep all clipboard access user-triggered and all local path inspection inside the trusted Electron boundary.

**Non-Goals:**

- Watching the clipboard, capturing the screen automatically, OCR before model submission, reading folders/archives, or accepting executables.
- Changing `@` search into implicit content access.
- Adding a screenshot-tool-specific plugin or background process.

## Decisions

### Recover native dropped paths in preload and validate in the main process

The sandboxed preload exposes Electron's supported `webUtils.getPathForFile` wrapper, then sends only the recovered string to a main-process inspection handler. The handler canonicalizes and revalidates a regular file, mirrors the Core's denied path/name policy, checks the supported extension and byte limit, and returns a canonical attachment DTO with the same stable reference ID algorithm as Core. Core remains the authoritative second validation immediately before reading. Reading bytes directly in the renderer was considered, but local path references avoid base64 expansion for ordinary dragged files and preserve the established document reader.

### Reuse the existing attachment wire vocabulary

Dropped image/PDF files use `local_file_reference` + `source: everything` + `multimodal_read`; supported documents use `document_read`. Here `everything` denotes the existing validated-local-reference wire class even when the user supplied the path by drag rather than search. Adding a new source would require coordinated Core and gateway changes without improving the boundary.

### Read clipboard images on explicit paste through Electron

When a paste event advertises image data, the renderer prevents only that image paste and invokes a main-process clipboard read. The main process converts the current native image to PNG, enforces the image byte limit, and returns an `inline_attachment` using the existing `airi_inline` compatibility source. Ordinary text paste does not invoke the bridge and remains native. Processing the DOM clipboard blob alone was considered, but Electron's native image path is more reliable for Windows DIB variants produced by Snipaste.

### Keep staging state in the existing attachment list

The current resource panel and retry state are extended to accept both local-file and inline-image attachment shapes. Each chip exposes metadata-only versus readable state, and readable items can be removed before send. No bytes are read from a dragged local file until Core handles the submitted turn; the pasted screenshot bytes are necessarily captured at paste time but are not sent until submission.

### Align the Core request decoder with the bounded attachment envelope

The existing 512 KB WebFlux codec limit rejects ordinary screenshots before the attachment resolver can enforce its 5 MB per-item and 10 MB aggregate rules. The Core codec limit is therefore raised to an environment-overridable 12 MB, matching the loopback gateway's already bounded multimodal request envelope. This is a decoder ceiling, not expanded file authority: the gateway marker check, attachment allowlists, path policy, decoded byte limits, and local-only routing remain authoritative.

### Activate the existing text provider only from the trusted local launcher

The machine already keeps its DeepSeek API key in a repository-external file. The local Core launcher now enables `deepseek-v4-flash` only when that file exists. Official provider behavior and a live request confirm DeepSeek V4 is text-only, so it is not declared as a vision model and no fake vision fallback is installed. The renderer and gateway receive neither the key nor its contents; if the file is absent, Core remains in mock mode.

### Route extracted documents independently from visual attachments

The attachment resolver produces plain `TextContent` for approved TXT/Markdown/CSV/JSON/YAML/DOCX documents and image/PDF content for visual attachments. Model routing therefore requires a declared vision route only when resolved content contains an image or PDF. A document-only turn can use the configured text model after local extraction; a screenshot/image/PDF turn without a vision provider fails before a provider call with an actionable configuration error. This preserves useful document reading without falsely claiming a text-only model can see pixels.

## Risks / Trade-offs

- [The Electron bridge and Java policy drift] → Share equivalent allow/deny fixtures in focused tests and keep Core revalidation authoritative.
- [PNG conversion makes a large screenshot exceed 5 MB] → Reject it before staging with an actionable message rather than silently compressing or truncating it.
- [A clipboard changes between paste event and IPC read] → Invoke the read immediately and present the captured attachment name/preview state; never claim access to later clipboard contents.
- [Inline screenshot data increases renderer memory and persisted retry state] → Bound each image at 5 MB and total attachment content at the existing 10 MB Core limit; do not persist drafts across application restarts.
- [The legacy `everything` source name is semantically broad] → Treat it as a wire-compatibility detail and label the user-visible origin as “拖入文件”.
- [A larger decoder ceiling could admit bigger non-attachment requests] → Keep the 12 MB ceiling configurable and retain the gateway's 1 MB default for turns without a validated readable-attachment marker.
- [Provider capability metadata can drift] → Keep the primary and vision capability lists explicit, validate them with a live request, and do not infer vision support from a model name alone.
- [A local launch could unintentionally require cloud access] → Enable the hosted provider only when the established external key file exists, keep offline mock fallback otherwise, and never copy credentials into the repository or renderer.

## Migration Plan

1. Add and test the trusted attachment inspection and clipboard-image IPC bridge.
2. Extend the native composer attachment model and drag/paste interactions without changing existing `@` behavior.
3. Run desktop unit, preload, type, build, gateway, and Core attachment tests; then restart only the local Meguri desktop stack.
4. Roll back by removing the new bridge handlers and composer events; existing metadata-only Everything search and text turns continue to work.
