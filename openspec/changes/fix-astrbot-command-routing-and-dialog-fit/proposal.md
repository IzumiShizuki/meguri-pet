## Why

Two AstrBot-side defects surfaced from live chatter:

1. The conversation-card renderer produced images with missing text. The LLM
   sometimes emits both bracket groups of a bilingual pair on one physical line
   (`【中文】【日文】`). The parser only accepted one bracket group per line, so it
   paired lines 1/2 and 3/4 instead of translation/original. That mixed Chinese
   into the Japanese font run, and `NotoSansJP-VF.ttf` has no glyph for
   `变`/`钱`, rendering them as blanks. The same bug made an oversized pair look
   like it had one panel, so the fixed 42px font ran past the bottom of the game
   dialogue frame and the trailing lines were cut off.
2. `jrlp` never reached the animewife plugin. The Meguri gateway routes every
   private message, and it claimed the message before the wife plugin could act,
   so the user got an ordinary chat reply instead of a wife card. The wife
   plugin's own gap was smaller but real: it registered no `jrlp` behaviour that
   matched the user's expectation, matched command text case-sensitively, and let
   the default LLM run even when it did own the message.

## What Changes

- Flatten full-width bracket groups in reading order before pairing, so a line
  holding several groups yields the same pairs as the canonical one-group-per-line
  layout; keep rejecting stray prose and odd group counts.
- Accept an unbracketed bilingual reply (a bare Chinese line followed by a Japanese
  line, as some Core direct replies arrive) by pairing on script, while still
  rejecting ordinary multi-line text.
- Shrink the bilingual fonts until the pair block fits the dialogue frame instead
  of drawing at a fixed size and clipping, and open `.ttc` collections through an
  index that actually carries the required glyphs.
- Render a Meguri turn from a high-priority decorator that stops the event
  afterwards, so other decorators cannot inject components into or replace the card.
- Give the gateway an ownership check: a message that belongs to another
  plugin's registered command is released before routing, and the gateway also
  accepts an explicit release list for plugins that match commands without
  registering a `CommandFilter`.
- Treat `jrlp`/`jrlb` as "today's wife": read today's wife when there is one and
  draw when there is not. Matching is case-insensitive, private chats get their
  own state scope, and a message the plugin owns suppresses the default LLM.
- Reconcile the repository with the two fixes the server had accumulated, so the
  server runs the repository files byte-for-byte.

## Capabilities

### New Capabilities

- `astrbot-command-ownership-priority`: Decides which plugin owns an inbound
  AstrBot message and guarantees a command that belongs to another plugin is
  never intercepted by the Meguri gateway.
- `astrbot-bilingual-dialog-fit`: Keeps a rendered bilingual conversation card
  complete — language-correct pairing and text that stays inside the frame.

### Modified Capabilities

- None.

## Impact

- Affected code: `adapters/astrbot/astrbot_plugin_chuanhuatong/`,
  `adapters/astrbot/astrbot_plugin_meguri_gateway/`, and the live
  `astrbot_plugin_animewifex` copy under `adapters/astrbot/astrbot_plugin_animewifex/`.
- Affected tests: AstrBot renderer, pagination, gateway, plugin entrypoint, and a
  new animewife alias/dispatch module.
- No public API, protocol, or dependency change. Deploying requires replacing the
  four plugin files and the gateway `passthrough_commands` release list on the
  server, then restarting the AstrBot container.
