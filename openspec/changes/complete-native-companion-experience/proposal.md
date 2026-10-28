## Why

Meguri's native desktop frontend already handles conversation and local integration, but several companion features remain unavailable or only exist in the AIRI fallback. The user requested completion of the non-platform, non-provider gaps identified against MeaPet mobile and a clear explanation of why formal memory is disabled.

## What Changes

- Add a usable Live2D renderer with host-managed model import, pointer following and model-defined touch reactions while preserving PNG character support.
- Connect the desktop to the authoritative memory service through verified identity and explicit user consent, and provide memory status, inspection, deletion and summary controls.
- Render assistant replies as safe Markdown and LaTeX with accessible text selection and copy controls.
- Add local speech-resource management, readiness feedback, speech speed and independent desktop/Galgame speech preferences using the existing local environment.
- Add chat wallpaper import, blur, bubble opacity and preset palettes alongside system/light/dark modes.
- Add first-run privacy choices, a readable data policy, consent withdrawal, sanitized diagnostic export and GitHub release checks.
- Produce an implementation and verification report with evidence and any resource or deployment limitations.
- Exclude new operating-system clients and main language-model/provider configuration as requested.

## Capabilities

### New Capabilities

- `native-live2d-companion`: Host-managed Live2D models and interactive character rendering.
- `native-desktop-memory`: Authoritative memory consent, identity, inspection and management in the native desktop client.
- `native-rich-conversation`: Safe Markdown/LaTeX rendering and copying.
- `native-speech-resources`: Local voice resources, model status and playback controls.
- `native-chat-personalization`: Wallpaper, blur, opacity and palette preferences.
- `native-privacy-and-updates`: First-run privacy choices, policy, diagnostic export and release detection.

### Modified Capabilities

None; the main spec directory contains no published capability specs for these surfaces.

## Impact

Primarily `apps/desktop-meguri`, the trusted desktop gateway in `apps/desktop-airi`, and the existing authoritative memory/local TTS seams. Shared desktop contracts and IPC bridges will be extended with named operations. Renderer dependencies may be added for Markdown, math and Live2D; existing local SDK/runtime resources will be reused where available. No production deployment, analytics integration, remote message delivery or platform expansion is part of this change.
