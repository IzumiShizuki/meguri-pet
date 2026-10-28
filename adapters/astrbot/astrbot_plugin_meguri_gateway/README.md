# Meguri 网关

这是 AstrBot 的 Meguri Gateway 插件。它负责把 AstrBot 消息转发给
Meguri Core，并提供两条 TimePrism 功能：

- 桌面端创建的待办到时间后，通过 Core 投递到指定 QQ 私聊。
- 允许指定的 QQ 私聊用户创建和确认一次性 TimePrism 待办。

## 最常用的配置

如果你只想让桌面端的待办提醒发送到 QQ，请在 AstrBot 的插件配置中填写：

```text
timeprism_reminders_enabled = true
timeprism_owner_user_id = 桌面端 Shizuki 主账号 ID
timeprism_reminder_target = AstrBot 的 QQ 私聊 FriendMessage origin
```

其中：

- `timeprism_owner_user_id` 必须与 Meguri 桌面端“Shizuki”设置中显示的用户 ID
  和“主账号”绑定一致。
- `timeprism_reminder_target` 不能只写 QQ 号，必须填写 AstrBot 实际收到的
  私聊消息来源完整值，通常形如 `platform:FriendMessage:sender`。
- 还必须让 Meguri Core 接收桌面端上传的提醒，并正确配置 AstrBot 使用的
  Core 共享令牌。

桌面端负责 Shizuki 登录、主账号绑定、本机 Core 上传和待发队列；AstrBot
插件负责从 Core 读取提醒并发送 QQ。两边都配置后，提醒才会真正到达 QQ。

## QQ 私聊创建待办

如果还希望从 QQ 私聊中创建待办：

```text
timeprism_todo_creation_enabled = true
timeprism_owner_user_id = 桌面端 Shizuki 主账号 ID
timeprism_todo_operator_senders = [允许操作的完整私聊发送者]
```

`timeprism_todo_operator_senders` 使用：

```text
platform:bot_account:sender_id
```

例如不要只填写 QQ 号。群聊不能创建或确认待办。

用户发送类似下面的内容：

```text
提醒我明天上午九点开会
提醒我睡觉
```

缺少日期时默认今天；缺少时间时会自动推断。睡觉类提醒默认使用
Asia/Shanghai 的 12:00；如果当天 12:00 已经过，则安排到下一天 12:00。

插件会先返回草稿预览，然后使用预览中的命令确认、修改或取消：

```text
/meguri todo confirm <草稿编号>
/meguri todo edit <草稿编号> <新的提醒描述>
/meguri todo cancel <草稿编号>
```

## 重要的默认值

大多数功能默认关闭：

- `timeprism_reminders_enabled`：QQ 到点提醒，默认关闭。
- `timeprism_todo_creation_enabled`：QQ 私聊创建待办，默认关闭。
- `route_all_private_messages`：是否接管所有私聊，默认关闭。
- `allow_group_messages`：是否允许群聊中的 `/meguri`，默认关闭。
- `remote_commands_enabled`：远程开发命令，默认关闭。

普通聊天默认需要使用 `/meguri` 前缀。不要为了启用待办提醒而打开
`route_all_private_messages`，这两个开关是不同功能。

## Core 和密钥

默认 Core 地址是：

```text
http://127.0.0.1:18080
```

共享令牌和身份盐值应使用环境变量或外部文件配置，不要直接写入插件配置或
提交到 Git：

```text
MEGURI_ASTRBOT_SHARED_TOKEN_FILE
MEGURI_IDENTITY_SALT
```

修改插件配置后，重启 AstrBot。桌面端停止或电脑关机期间不会产生新的桌面
提醒；需要全天候提醒时，还需要独立运行的服务端调度器。

## 配置字段对照

AstrBot 配置页中的 key 保持英文，因为插件代码依赖这些字段名；每个字段的
说明已经改为中文。与 QQ 待办最相关的字段是：

| 配置项 | 作用 |
| --- | --- |
| `timeprism_reminders_enabled` | 开启 QQ 到点提醒 |
| `timeprism_todo_creation_enabled` | 开启 QQ 私聊创建待办 |
| `timeprism_owner_user_id` | 桌面端绑定的 Shizuki 主账号 ID |
| `timeprism_reminder_target` | QQ 私聊完整目标 |
| `timeprism_todo_operator_senders` | 允许创建待办的私聊发送者 |
| `timeprism_reminder_poll_seconds` | 提醒轮询间隔 |
| `timeprism_reminder_max_age_minutes` | 过期提醒最大保留时间 |
