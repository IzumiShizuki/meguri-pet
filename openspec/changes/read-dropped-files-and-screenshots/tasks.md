## 1. Trusted desktop attachment boundary

- [x] 1.1 Add a tested main-process policy that canonicalizes dropped local paths, mirrors the Core deny rules, classifies supported content, enforces 1 MB document and 5 MB multimodal limits, and creates Core-compatible stable references.
- [x] 1.2 Add trusted IPC handlers for dropped-file inspection and explicit clipboard-image capture, including clear errors and no background clipboard monitoring.
- [x] 1.3 Expose only the required file-path, inspection, and clipboard-image methods through the sandboxed preload bridge and TypeScript contract.
- [x] 1.4 Align the Core WebFlux decoder ceiling with the existing 12 MB gateway envelope so valid screenshots reach the bounded attachment resolver.
- [x] 1.5 Make the trusted local Core launcher activate the existing `deepseek-v4-flash` text route when its repository-external key file is present, without falsely declaring DeepSeek V4 as a vision model or exposing credentials to the renderer.

## 2. Native composer ingestion

- [x] 2.1 Extend the attachment type and normalization helpers for metadata-only local references, readable local files, and bounded inline screenshots.
- [x] 2.2 Add drag-enter/drop handling, visible drop affordance, deduplication, multiple-file bounds, and recoverable validation feedback to the Meguri chat dock.
- [x] 2.3 Add image-aware paste handling that preserves ordinary text paste and stages Snipaste/Windows clipboard images as removable readable attachments.
- [x] 2.4 Update attachment chips, hints, user-message summaries, retry state, and accessible labels to distinguish “仅引用” from “将读取内容”.
- [x] 2.5 Route extracted text documents through the configured text model while keeping image/PDF turns behind an explicitly configured vision-model gate.

## 3. Verification and local handoff

- [x] 3.1 Add unit and contract coverage for policy allow/deny cases, preload methods, attachment normalization, readable payload delivery, and composer drag/paste wiring.
- [x] 3.2 Run desktop unit/preload/type/build tests plus focused gateway and Java Core attachment tests, then validate the OpenSpec change strictly.
- [x] 3.3 Restart the local Meguri gateway and Electron app, and manually verify one dropped document/image and one pasted screenshot reach the local Core attachment route.
