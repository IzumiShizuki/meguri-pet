## Why

Meguri can already accept an explicitly pasted clipboard screenshot, but the user must leave PixPin, focus the chat composer, paste, and send. The user wants a deliberate screenshot-to-pet flow for both asking a question about a selected region and handing the image directly to Meguri. PixPin 3.5.5.1 is now installed locally; its documented script interface provides screenshot triggers, but the screenshot completion and handoff behavior still needs a version-specific live check.

## What Changes

- Add a user-initiated **截图问 Meguri** action: open PixPin selection, receive one completed image, and stage a removable attachment in the current composer with focus ready for a question.
- Add a distinct **截图直接发送** action: after the user completes selection, send that one image with a visible default question through the existing desktop turn and multimodal attachment path. The action is explicitly chosen by the user and never runs on an ambient timer.
- Preserve the existing manual path: PixPin copies an image, then the user pastes it into Meguri. Make it the documented fallback when PixPin automation is unavailable.
- Add PixPin availability/configuration feedback, one-shot capture lifecycle, cancellation, timeouts, duplicate/stale-clipboard protection, and clear vision-route errors.
- Investigate PixPin-initiated custom action handoff after the PixPin installation is available. Ship a dedicated Meguri shortcut for importing the latest copied screenshot if PixPin does not expose a reliable post-selection callback; do not depend on undocumented callback behavior.
- Keep observed-window/automatic Glance capture as a subsequent change. This change may reuse the observation privacy boundary but does not enable continuous screen observation, `screen_read`, or `screen_context` by default.

## Capabilities

### New Capabilities

- `pixpin-screenshot-handoff`: User-triggered PixPin capture, one-shot image receipt, draft/submit behavior, failure handling, and local permission boundaries.

### Modified Capabilities

None. The existing clipboard-paste contract remains valid and is reused by the new flow.

## Impact

- Desktop Electron main/preload/shared bridge: PixPin executable discovery, command invocation, one-shot result receipt, and trusted IPC.
- Desktop Vue renderer and conversation store: two entry points, capture state, attachment staging, and explicit send behavior.
- Existing attachment limits and local Core multimodal route are reused; no new hosted upload or PixPin private plugin is required.
- Documentation/configuration: official PixPin installation and script setup, troubleshooting, and manual fallback.
- Local prerequisites for end-to-end verification: PixPin 3.5.5.1 is installed at `D:\Program Files\PixPin`; a vision-capable model route must be configured and tested separately. This planning work does not alter either program's runtime configuration.

## Sources

- Product intent: [Meguri desktop awareness baseline](https://app.notion.com/p/3b9a3636596381b5b103cde69ae20390) and [Capability Runtime boundary](https://app.notion.com/p/3a8a3636596381f09291f82e3d2cc47e).
- PixPin supported scripting: [official script reference](https://pixpin.cn/docs/configuration/script) and [official action configuration](https://pixpin.cn/docs/configuration/actions).
- Existing repository contracts: `openspec/changes/read-dropped-files-and-screenshots/` and `openspec/changes/add-desktop-observation-runtime/`.
