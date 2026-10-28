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

## Owner TimePrism QQ reminders

The desktop publishes selected main-account Todo occurrences to the remote
Core's authenticated `/v1/reminders/timeprism` inbox. To enable QQ delivery,
deploy that Core change with `MEGURI_REMINDER_OWNER_SHIZUKI_USER_ID` set to the
same positive Shizuki user ID selected in desktop settings, then configure this
plugin with `timeprism_reminders_enabled=true`,
`timeprism_owner_user_id=<same ID>`, and `timeprism_reminder_target` set to one
exact AstrBot `platform:FriendMessage:sender` origin for the owner's QQ private
chat. The target must come from a real AstrBot message origin; a QQ number alone
is not a complete origin. The plugin never accepts a destination in reminder
payloads. It keeps a delivery cursor in `timeprism_reminder_state_file` and
retries when Core or QQ is unavailable. A send interrupted after QQ accepted it
but before the cursor was persisted can produce one duplicate. Old events are
skipped after `timeprism_reminder_max_age_minutes` (default 30). No new events
are generated while the desktop is stopped.

## Create TimePrism reminders from private chat

The gateway can prepare and confirm one-time TimePrism Todos for one configured
owner. This feature is disabled by default. Enable
`timeprism_todo_creation_enabled`, set `timeprism_owner_user_id` to the same
positive Shizuki account ID configured in Core, and add the exact private sender
as `platform:bot_account:sender_id` under `timeprism_todo_operator_senders`.
Group messages cannot create or confirm drafts. A request such as
`提醒我1点睡觉` gets a date and AM/PM inferred from the next future
Asia/Shanghai occurrence and returns a preview without writing anything.

Use the commands shown in the preview:

```text
/meguri todo confirm <草稿编号>
/meguri todo edit <草稿编号> <新的提醒描述>
/meguri todo cancel <草稿编号>
```

Before enabling the feature, configure the Core process with absolute paths to
two external files containing the Shizuki owner email and password:
`MEGURI_TIMEPRISM_OWNER_EMAIL_FILE` and
`MEGURI_TIMEPRISM_OWNER_PASSWORD_FILE`. Keep those files outside the repository
and readable only by the Core service account. Core also needs its existing
`MEGURI_REMINDER_OWNER_SHIZUKI_USER_ID`, authenticated AstrBot shared-token
configuration (`MEGURI_CORE_SHARED_TOKEN_FILE`), and writable
`MEGURI_REMINDER_DIR`. Todo creation always verifies the token and exact
owner/client/session identity, including when legacy chat uses
`MEGURI_CORE_AUTH_REQUIRED=false`. Restart Core and AstrBot after configuration
changes. Missing writer credentials fail closed; the existing reminder-delivery
poller is configured independently.

`MEGURI_TIMEPRISM_HTTP_TIMEOUT_MS` defaults to 15000 and bounds each HTTP exchange
through the complete response body, including a body stalled after headers.
Confirmed receipts use the draft's minute precision even if the website returns
zero seconds. The persistent write journal retains verified website data;
uncertain writes reconcile the original request marker before any retry.

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

## 按人格选择 Meguri 或 AstrBot

同一个机器人可以让指定人格使用 Meguri，其余人格保留 AstrBot 自己的模型、
工具和上下文。在 Meguri 网关插件配置中设置：

```json
{
  "persona_routing_enabled": true,
  "meguri_persona_ids": ["爱莉"],
  "route_all_private_messages": true
}
```

把示例中的 `爱莉` 换成 AstrBot 中实际的人格 ID（通常就是人格名称），精确匹配、
区分大小写。多个 Meguri 人格可填入同一列表。其他人格的普通消息交回 AstrBot，
不会创建 Core 回合或 Meguri 待办草稿。需先在 AstrBot 配置好默认模型。
群聊还需 `allow_group_messages=true` 和 `route_all_group_messages=true`；
人格名单不会绕过原有发送者白名单、群聊限制或自动接管开关。

每条消息读取自己会话的当前生效人格，支持默认人格和会话强制人格规则；切换人格后，
下一条消息即按新选择处理。使用 AstrBot v4.28.1 的原生人格解析接口；旧版或接口
读取失败时，普通消息交回 AstrBot。显式 `/meguri ...` 不受人格名单限制，仍需
通过原有权限检查。已有其他插件的命令仍优先处理。

`persona_routing_enabled` 默认关闭，关闭后恢复原来的路由行为；开启后如果
`meguri_persona_ids` 为空，普通聊天全部交回 AstrBot。`[%None]`（不使用人格）
始终使用 AstrBot。切换后端不会迁移或合并 AstrBot 与 Core 的历史、长期记忆；
Meguri 的后台日报和提醒投递配置独立于当前聊天人格。

上游接口依据：[人格解析](https://github.com/AstrBotDevs/AstrBot/blob/v4.28.1/astrbot/core/persona_mgr.py)
和[会话管理](https://github.com/AstrBotDevs/AstrBot/blob/v4.28.1/astrbot/core/conversation_mgr.py)。

## Command ownership

With `route_all_private_messages=true` (or `route_all_group_messages=true`) the
gateway sees essentially every message, so it must yield messages that belong to
another plugin instead of answering them. Two mechanisms decide that:

- **Per-event ownership**: AstrBot's `activated_handlers` is authoritative for
  registered command, command-group and regex handlers. AstrBot has already
  applied platform, permission, message-type, plugin and session filters. The
  gateway does not evaluate those filters twice or alter parsed parameters.
- **Automatic triggers**: matched Bilibili share-card, media-parser,
  image-search and meme listeners have narrow checks against their existing
  instances/settings. The gateway does no parsing downloads or resource loading.
  General ALL-message listeners alone do not claim ordinary chat.
- **Legacy discovery**: when per-event metadata is unavailable, command names
  and aliases are read from other plugins' `CommandFilter`/`CommandGroupFilter`,
  cached for 60 seconds. Discovery failure leaves configured aliases working.
- **`passthrough_commands`**: an explicit release list, prefix-matched and
  case-insensitive, for plugins that match commands as plain text instead of
  registering a `CommandFilter`. `astrbot_plugin_animewifex` is such a plugin, so
  `jrlp`, `jrlb`, and its Chinese commands belong in that list.

Matching accepts bare or slash-prefixed commands, case-insensitively, and
whitespace-delimited arguments (`查老婆 @某人`). An explicit `/meguri ...` message
is never released, including after AstrBot strips its wake prefix. Released
messages suppress AstrBot's default LLM and leave dispatch running, so the owning
plugin answers without a Core turn or a competing ordinary-chat reply.

The installed plugin support matrix and complete command/alias catalogue are in
[AstrBot 个人模式命令表](../../docs/astrbot-personal-commands.md). Private support
does not enable group administration, unavailable platform APIs, or AstrBot LLM
tools inside Meguri Core.

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
