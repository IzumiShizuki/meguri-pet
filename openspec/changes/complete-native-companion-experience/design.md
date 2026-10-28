## Context

See `proposal.md` for motivation. The native frontend is Electron/Vue with host-owned version-1 preferences and named IPC. The AIRI checkout at `D:/program/airi-meguri` already owns Live2D rendering and Cubism Core. Meguri currently has PNG assets only. GPT-SoVITS and a dedicated Python environment already exist; desktop playback talks to its loopback bridge. Formal memory lives in Python `native_pgvector`, reached from Java through an authenticated internal bridge; desktop hello currently opts out, and the launcher does not start/configure that bridge.

## Goals / Non-Goals

**Goals:** Integrate through existing runtime authority seams; retain PNG and local text operation during optional resource failures; show the actual memory/voice/provider state; support migration of existing preferences without losing user choices.

**Non-Goals:** Operating-system expansion, provider-selection UI, production deployment/database migrations, replacing the authoritative memory provider, automatically approving model-generated facts, copying third-party character assets or adding analytics.

## Decisions

1. Keep Electron main responsible for resource selection, managed files, diagnostics and release requests. Preload exposes named operations rather than a generic filesystem/network API. Child agents own independent modules; the primary agent owns shared contracts, channel/preload wiring, main integration and evidence.
2. Extend existing preference groups with chat wallpaper/blur/opacity/palette, character renderer selection, speech speed/surface switches, memory interval and privacy acceptance/remote-chat/history/update choices. First-run policy approval gates remote conversation; memory is an explicit total-use switch because the existing protocol binds memory read and candidate write together. Renderer consent alone never grants server authority.
3. Reuse AIRI stage Live2D components and its installed Core. Resolve the AIRI package from a configurable local checkout during build, never create a competing Live2D engine. Imported models are copied into an app-managed store after path/resource validation. User assets are not bundled as Meguri's canonical character. Runtime failure recovers to PNG. If additional AIRI APIs are required, keep integration transformations/adapters in this repo without overwriting the external checkout.
4. Keep GPT-SoVITS as the local voice runtime and add managed profile packages with weights, reference audio, language and verified HTTPS manifest downloads. Mobile VITS/ONNX packs are not compatible and must be rejected clearly; supported profile import must actually affect synthesis. Existing default resources are read-only. Loopback management operations have a host-only authorization boundary.
5. Add scoped Java desktop-memory management endpoints and typed Python internal bridge methods. Require trusted caller identity, the server's user/client authorization and `native_pgvector` availability. Reuse candidate review policy/audit and user-owned soft-delete/restore/export. Automatic summaries run only for the current consented session with a stable checkpoint request identifier. Provide an opt-in launcher for an already configured authoritative Python service; missing database credentials are displayed as unavailable.
6. Use a shared safe Markdown/math component for assistant replies on both conversation surfaces. Disable raw HTML, unsafe links, remote images and trusted KaTeX commands. External links pass existing verified Electron window-opening behavior, and navigation is locked to app content. Copy retains original source.
7. Use reusable settings subcomponents for memory, voice, avatar, personalization, privacy and updates. Preserve current desktop geometry and style. Palette variables cover light and dark contrast; Windows accent provides the system palette when available. Wallpaper is image-validated and bounded before storage.
8. Release checking is a GitHub API read with strict version/URL validation, no download/install. Export diagnostics from a small whitelist and bounded redacted errors, excluding conversation/private account files. No privacy promise claims remote AI data stays on-device.

## Risks / Trade-offs

- Existing local authority configuration is absent → expose truthful health and configuration guidance; verify offline stubs without writing production memory.
- No licensed Meguri Live2D asset exists → complete user-import capability and use synthetic/authorized fixtures for checks; document asset requirement separately from runtime support.
- Native build references an existing AIRI checkout → configurable source root and build diagnostics, preserving the existing installed runtime instead of reinstalling it.
- Speech profile activation can partially fail → verify complete packages and restore the previous active profile after errors.
- The repository has no published desktop-release infrastructure → a 404 is reported as no release; release creation/signing remains a separate action.
- Concurrent implementation touches shared UI → ownership boundaries and primary-agent integration prevent conflicting edits.

## Migration Plan

1. Extend preference normalization with compatible defaults and first-run policy acceptance.
2. Add optional runtime modules and wire IPC/API boundaries.
3. Integrate settings and character/conversation surfaces, then build using existing Node/Python/Java tools.
4. Run targeted unit/security/integration checks and isolated Electron checks without production writes; save evidence in the feedback report.
5. Preserve PNG, current speech profile and existing settings as rollback paths. Keep the OpenSpec change open unless the user explicitly asks to archive it.
