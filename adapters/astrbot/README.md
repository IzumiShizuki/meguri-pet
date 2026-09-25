# AstrBot Meguri gateway

This directory contains the loadable AstrBot gateway plugin for Meguri. AstrBot
is only a messaging adapter: `meguri-core` owns character turns and Meguri Relay
owns device matching, confirmation, task state, and audit. The plugin never
connects directly to a home computer or starts Codex itself.

## Current boundary

- `main.py` is a real AstrBot `Star` entrypoint and intercepts selected message
  events before AstrBot's default LLM pipeline.
- Ordinary character chat is sent to the loopback Java Core at
  `http://127.0.0.1:18080` by default.
- Remote commands are disabled by default. When enabled, they call a separately
  configured Meguri Relay and require an exact remote-operator sender binding.
- Platform identifiers are HMAC-derived before they reach Core. Private/group
  sessions and separate bot accounts do not share short context implicitly.
- Duplicate platform message IDs are ignored for ten minutes. Relay task
  previews also use the platform message ID as the idempotency key.
- Sprite rendering is declared available and the validated Core
  `runtime_state`/resolved `expression` are attached as
  `meguri_render_payload` for the Gal renderer. Voice and screen context remain
  unavailable.
- Every asynchronous Turn starts with canonical `/v1/hello`. The gateway sends
  separate HMAC-derived platform actor, client instance, session, and Meguri
  user identities, then persists the selected protocol minor and server
  capability revision in the Turn checkpoint file.
- HTTP 410 event cursors restore the authoritative session snapshot and resume
  from its sequence. The Python gateway has no direct TTS/animation side-effect
  dispatcher; accepted event checkpoints are persisted before the completed
  Turn is handed to AstrBot's renderer.

The plugin is not installed into an existing AstrBot instance by repository
tests. Production installation into `/opt/astrbot/data/plugins` remains
approval-gated, and this code does not modify AstrBot data or host networking.

Because this plugin lives in a monorepo subdirectory, AstrBot URL/marketplace
installation must not point at the monorepo root. Build the standalone archive
first, then inspect it and install it through the separately approved AstrBot
workflow:

```powershell
D:\environment\anaconda3\envs\py314\python.exe `
  adapters\astrbot\package_plugin.py `
  --output output\astrbot_plugin_meguri_gateway.zip
```

The archive contains a top-level `astrbot_plugin_meguri_gateway/` directory
with `main.py`, metadata, configuration schema, requirements, and supporting
modules. It also includes the Bilibili daily-card template and the OFL-licensed
Noto Sans SC font under `assets/`; `bilibili_daily_card.py` performs bounded
Pillow layout and writes rendered cards to `daily_report_render_directory`.
Rendering failures leave delivery pending for a later retry, while older
reports without `render_payload` continue to use text-only delivery.
Packaging does not read or include local configuration, tokens, or
AstrBot data. This upload package intentionally has no repository update source:
do not use AstrBot's update action until the plugin has a dedicated repository
or release download URL whose archive root is the plugin itself.

## Routing and identities

The default policy routes only explicit `/meguri` messages. Set
`route_all_private_messages=true` only when the connected AstrBot account is
intended to behave as a dedicated Meguri chat. Group messages remain disabled
unless `allow_group_messages=true`, and even then they must start with
`/meguri`.

`allowed_senders` and `remote_operator_senders` use this exact form:

```text
platform:bot_account:sender_id
```

An empty `allowed_senders` list does not add an adapter-level chat restriction;
AstrBot's own platform rules still apply. Remote commands are stricter: an
empty `remote_operator_senders` list denies them. AstrBot's global administrator
flag never grants remote-development authority because its sender IDs are not
namespaced by platform and bot account.

`identity_bindings` persist cross-platform bindings in AstrBot's plugin config.
Unbound identities receive opaque per-platform, per-bot-account user IDs.

An identity salt is required. It can come from `MEGURI_IDENTITY_SALT` or the
configured `identity_salt_file`. Tokens are never stored in plugin config;
environment variables or the direct `core_token_file` setting point to secret
files instead:

```text
MEGURI_ASTRBOT_SHARED_TOKEN_FILE
MEGURI_RELAY_TOKEN_FILE
```

## Command ownership

With `route_all_private_messages=true` (or `route_all_group_messages=true`) the
gateway sees essentially every message, so it must yield messages that belong to
another plugin instead of answering them. Two mechanisms decide that:

- **Discovery**: command names and aliases are read from every other plugin's
  registered `CommandFilter`/`CommandGroupFilter`, cached for 60 seconds. A
  discovery failure leaves the configured list authoritative rather than routing
  everything to Meguri.
- **`passthrough_commands`**: an explicit release list, prefix-matched and
  case-insensitive, for plugins that match commands as plain text instead of
  registering a `CommandFilter`. `astrbot_plugin_animewifex` is such a plugin, so
  `jrlp`, `jrlb`, and its Chinese commands belong in that list.

Matching accepts the bare command and the command followed by arguments
(`查老婆 @某人`). An explicit `/meguri ...` message is never released, even when the
text after the prefix looks like another plugin's command. Released messages are
not blocked from AstrBot's default LLM and are not stopped, so they behave exactly
as if the gateway had not run.

## Chinese/Japanese learning replies

`bilingual_zh_ja=true` makes the gateway request the Core reply profile
`zh_ja_pairs`. The five-field `LlmResponse` contract is unchanged: Core writes
paired lines inside `reply`, with a faithful Chinese translation first and the
natural Japanese original second. Translation stays in the authoritative LLM
turn; the image renderer only validates, splits, and lays out complete pairs.

```text
【今天也辛苦了。】
【今日もお疲れさま。】
```

A pair may also arrive with both groups on one physical line
(`【今天也辛苦了。】【今日もお疲れさま。】`); the renderer flattens the groups in
reading order before pairing, so both layouts produce the same translation/original
pairs. Pairing by group rather than by line is what keeps Chinese out of the
Japanese font run — a mixed run would draw characters the Japanese font does not
cover as blanks. Some Core direct replies (a weather answer, for example) arrive
with no brackets at all as a bare Chinese line followed by a Japanese line; those
are paired by script so the reply still renders as a learning card, while ordinary
multi-line text is left alone. An oversized pair is paginated one pair per panel,
and the panel's font size shrinks until the block fits the dialogue frame rather
than being clipped.

A Meguri turn is rendered from a decorator that runs before other plugins'
decorators and then stops the event, so the reply cannot be polluted by, or
replaced with, another plugin's decorated output; non-Meguri turns keep the
original low-priority path.

The line order carries the language meaning, so the rendered message never
shows language-name labels. Other clients remain on the default reply format. Memory candidate summaries
also remain concise Chinese rather than inheriting the display format.

## Commands

```text
/meguri chat <message>
/meguri status
/meguri mode work|private|sleep|event [2h]
/meguri outfit auto|01..06 [2h]
/meguri relation sibling|pursuit|lover [2h]
/meguri reset
/meguri devices
/meguri run [--device <device_id>] <task>
/meguri confirm <draft_id> <code>
/meguri task <task_id>
/meguri cancel <task_id>
```

The remote flow is deliberately two-phase. `/meguri run` requests a Relay-owned
preview containing the matched computer, command summary, permissions, expiry,
and confirmation code. Only `/meguri confirm` may turn that draft into a queued
task.

## Relay HTTP contract

The adapter defines the client side of the following versioned endpoints:

```text
GET  /v1/remote/devices
POST /v1/remote/tasks/preview
POST /v1/remote/tasks/{draft_id}/confirm
GET  /v1/remote/tasks/{task_id}
POST /v1/remote/tasks/{task_id}/cancel
```

The Relay remains authoritative for authentication, device ownership, online status,
workspace/capability matching, confirmation validation, command expiry,
idempotency, and task state. The Relay must map the service token to an allowed
principal instead of trusting client-supplied identity headers. Every Relay URL,
including loopback, requires a file-backed access token; non-loopback URLs must
also use HTTPS. The preview request deliberately omits AstrBot's raw unified
message origin; proactive completion notifications need a separate opaque reply
binding rather than copying platform session identifiers into Relay task data.
