## Why

AstrBot's conversation-card plugin can render a long reply into an image, but a single oversized bilingual sentence is kept atomic and can overflow the card. When multiple cards are produced, the delivery path can also resend the first rendered image instead of continuing with the next page.

## What Changes

- Paginate oversized bilingual sentence pairs while preserving the translation/original ordering on every page.
- Render each page from its own text slice so later images continue from, rather than repeat, the first page.
- Deliver every generated image in page order and retain plain-text fallback when rendering fails.
- Add regression coverage for long single-sentence pagination and distinct consecutive image delivery.

## Capabilities

### New Capabilities

- `astrbot-dialog-image-pagination`: Defines bounded, ordered, non-repeating image pages for AstrBot conversation replies.

### Modified Capabilities

None.

## Impact

- Affected code: `adapters/astrbot/astrbot_plugin_chuanhuatong/meguri_render.py` and `main.py`.
- Affected tests: AstrBot renderer and plugin entrypoint tests.
- No public API or dependency changes are expected.
