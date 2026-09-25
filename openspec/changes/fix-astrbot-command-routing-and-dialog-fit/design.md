## Context

Two independent AstrBot surfaces, one shared symptom class ("my text/command did
not arrive as expected"):

| Symptom | Observed evidence | Root cause |
| --- | --- | --- |
| Characters missing in the rendered card; one sentence split across two images | Production log `23:59:17` shows the LLM reply as `【中文】【日文】` on one line per pair, with `清洗后长度=217` and `分割文本为 4 个片段` | `parse_bilingual_pairs` required exactly one `【...】` group per line, so it paired line 1 with line 2 across languages; the Japanese font then rendered Chinese text (`变`, `钱` are absent from `NotoSansJP-VF.ttf`), and the mis-detected pair count defeated pagination, letting a 42px block overflow the 210px-high dialogue box |
| `jrlp` answered with ordinary chat instead of the wife card | Production log `23:59:15` shows `[At:qq_official] jrlp` in a private chat, then the Meguri turn rendering | The gateway routes all private messages (`route_all_private_messages=true`) and stops the event, and the wife plugin only registers commands via plain text prefix matching with `EventMessageType.GROUP_MESSAGE` |

Measured on the live server, before the fix:

- `parse_bilingual_pairs` on the captured text returned **1** pair instead of **2**,
  with `CN = 又是同一句呢……】【また同じ言葉だね……` — both languages inside one string.
- `split_bilingual_text(..., 80)` returned **1** panel, so the whole reply rendered
  into one frame; the measured text height was **713px** against **210px** of box.
- `NotoSansJP-VF.ttf` is missing `变` and `钱`, exactly the characters the
  mis-paired Japanese line had to draw.

## Goals / Non-Goals

**Goals**

- A bilingual reply renders as complete, language-correct pages that fit the frame.
- A command owned by another plugin reaches that plugin, even when the Meguri
  gateway routes all private messages.
- `jrlp` resolves to the wife plugin in both group and private chats.

**Non-Goals**

- Changing the Meguri Core protocol, the five-field LLM response contract, or the
  `zh_ja_pairs` server-side format.
- Enabling new capability, TTS, attachment, or group-message exposure.
- Rewriting the animewife plugin's data model or moving its state files.

## Decisions

### 1. Flatten bracket groups per line instead of trusting line pairs

`parse_bilingual_pairs` now extracts **all** `【...】` groups from each physical
line in reading order, requires every non-empty line to consist solely of such
groups, and pairs sequentially. Both the canonical layout (one group per line)
and the packed layout (several groups per line) therefore produce identical pairs.
Rejected inputs are unchanged: stray prose, an empty group, or an odd group count
still return `None` so ordinary text is never misread as bilingual.

Alternative rejected: teach the renderer to split a packed group — that would
leave the pagination counter wrong and duplicate the parsing rule in two places.

### 2. Fit the bilingual block instead of clipping it

`_draw_textbox_layer` now measures the wrapped pair block and reduces the
bilingual font size until it fits `box_height - 2 * padding`, stopping at
`BILINGUAL_FONT_SIZE_MIN` so behaviour never becomes worse than a readable page.
The scale step is proportional and always shrinks by at least one point, so a
non-linear font metric cannot stall the loop.

### 3. Gateway releases messages another plugin owns

`MessageRoutePolicy.matches_passthrough` releases a message when its text equals
or starts with (plus a separating space) either a configured release entry or a
command discovered in AstrBot's handler registry. Discovery reads
`CommandFilter`/`CommandGroupFilter` names from every non-gateway
`AdapterMessageEvent` handler, is cached for 60 s, and degrades to an empty set
when AstrBot internals are unavailable. An explicit `/meguri ...` invocation is
never released.

Measured on the live server: the registry currently exposes **0** discoverable
commands, and `astrbot_plugin_animewifex` matches its commands as plain text
prefixes rather than through a `CommandFilter`. The configured release list is
therefore the mechanism that actually releases `jrlp` today; discovery is the part
that keeps working as other plugins adopt `CommandFilter`. This is why the
`passthrough_commands` entry stays and the schema now documents it.

Alternative rejected: hard-coding the wife plugin's Chinese command names in the
gateway. That couples two plugins and breaks as soon as either renames a command.

### 4. Wife plugin owns `jrlp` semantics

`jrlp`/`jrlb` map to a `today_wife` entry that reads today's wife when one exists
and falls through to the draw path otherwise, because that is the behaviour the
user asked for. Matching is case-insensitive for the ASCII aliases, `_session_key`
gives private chats a `private_<sender>` scope instead of crashing on a missing
`group_id`, and a message the plugin owns calls `should_call_llm(True)` so a
failure can never surface as an ordinary chat reply.

## Risks / Trade-offs

- **Release list drift** → discovery covers `CommandFilter` plugins; the list is
  documented in `_conf_schema.json` and covered by a regression test. A stale entry
  only means one command keeps its plugin (it never routes those to Meguri).
- **Font shrinking can look small on very long pairs** → bounded at 18px, and the
  page splitter already keeps one pair per panel, so shrinking is a last resort
  rather than the normal path.
- **Reading AstrBot internals** → the import is local, wrapped in `try/except`, and
  a discovery failure keeps the configured list authoritative instead of routing
  everything to Meguri.

## Migration Plan

1. Replace the affected plugin files and merge the `passthrough_commands` release
   list into the live gateway config; remove `__pycache__` for the touched plugins.
2. Restart the AstrBot container.
3. Verify: render the captured production text through
   `_render_pillow_panel` (expect 2 pages, sizes 42px and 36px, measured heights
   142px and 167px against 170px available), drive the live plugin with a private
   `jrlp` event (expect the draw path, `call_llm=True`, scope
   `private_<sender>`), and confirm the registered decorator priorities.
4. Rollback: restore from `data/plugin_backups/reconcile-<timestamp>/` (or the
   earlier `codex-<timestamp>/`) and
   `astrbot_plugin_meguri_gateway_config.json.bak-passthrough-*`, then restart.

## Repository / server reconciliation

The server copy of `astrbot_plugin_chuanhuatong` had accumulated two fixes the
repository lacked, so the first pass applied this change **surgically to the live
text** rather than deploying the repository files, which would have silently
reverted them. Both fixes are now part of the repository, and the server runs the
repository files byte-for-byte:

| Server-only behaviour | Why it matters | Where it now lives |
| --- | --- | --- |
| Decoration split into `_meguri_isolate_and_render` (`on_decorating_result(priority=1_000_000)`, `stop_event()` after rendering) and `_legacy_decorate_last` (`priority=-10`) | Renders a Meguri turn before `meme_manager@99999`, then stops the event so no other decorator injects components into or replaces the Gal card | `main.py`, covered by `tests/test_astrbot_chuanhuatong_decoration.py` |
| Unbracketed bilingual fallback (`_KANA_RE`/`_HAN_RE` script pairing for Core direct replies such as weather) | A bracketed-less `中文行\n日文行` reply is still rendered as a learning card instead of degrading to plain text | `meguri_render.py`, covered by the new `parse_bilingual_pairs` cases in `tests/test_astrbot_meguri_render.py` |

The repository keeps its own improvements that the server variant lacked (bounded
slicing for an oversized pair, and the `.ttc` face probe), so the merged files are
a strict superset of both sides. Post-merge verification on the live server:

- `_meguri_isolate_and_render` registered at priority `1000000` and
  `_legacy_decorate_last` at `-10`, exactly two decorators, and the shared
  `_decorate_and_render` present.
- The captured production text parses as 2 pairs / 2 panels; an unbracketed
  Chinese+Japanese reply parses as 1 pair / 1 panel.
- Rendering the captured text still produces 2 pages whose measured heights
  (142px, 167px) fit the 170px box at 42px and 36px.

Line endings were normalized to LF on both sides so the deployed artifact and the
repository file hash identically. Pre-merge states remain recoverable from
`tmp/live-diff/` (gitignored `before_*`/`deployed_*` pairs) and
`data/plugin_backups/reconcile-<timestamp>/` on the server.
