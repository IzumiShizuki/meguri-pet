## Why

The desktop can already locate local files and surface generated artifacts, but selected files reach the language model as metadata only. Users cannot reliably send image or document content to the pet, safely fall back when the primary model lacks multimodal support, or receive model-produced local files as interactive pet artifacts.

## What Changes

- Turn a dragged or `@`-selected safe local file into a bounded multimodal attachment when its type and configured model support it.
- Route unsupported primary-model multimodal requests to an explicitly configured `dsv4p` fallback; otherwise return a clear, non-silent failure without sending an altered request.
- Recognize safe local-file references returned by the model, then present them as blue clickable chat links and randomized default-application bubbles around the pet.
- Preserve confirmation, content-size/type limits, local-path safety gates, and metadata-only behavior for unsupported files.
- Add an opt-in prompt skill that tells agent-capable routes how to refer to already-approved local attachments and generated files without granting filesystem access.

## Capabilities

### New Capabilities

- `multimodal-local-file-delivery`: Send explicitly approved local image/document attachments to a compatible model, with an explicit fallback policy.
- `pet-file-artifact-presentation`: Present safe model-returned files as clickable chat links and temporary floating desktop-pet bubbles.
- `approved-file-reference-skill`: Give agent-capable model routes a constrained skill for referring to approved input and generated output files.

### Modified Capabilities

- None.

## Impact

- AIRI Stage Tamagotchi Electron IPC, renderer and Meguri provider adapter in `D:\program\airi-meguri`.
- Loopback desktop gateway, Java turn/LLM contracts, local-file policy, and configuration in this repository.
- Local deployment scripts and focused Java/TypeScript tests; no external filesystem upload endpoint or secret exposure is introduced.
