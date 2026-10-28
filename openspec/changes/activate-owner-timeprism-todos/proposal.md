## Why

用户要求继续全链路调整，并已指定管理员账号用于接通真实 Todo。实际服务未配置 owner、凭据或私聊操作者，写入功能仍关闭；Java 写入器也缺少直接 HTTP 级测试。

## What Changes

- 核对管理员账号及唯一真实私聊身份，配置 Core/AstrBot owner 和受限外部凭据文件，保留回滚资产。
- 补全真实 Java 写入器的本地 HTTP 测试；仅修复由测试复现的超时、回执或幂等问题。
- 使用合成事件、本地收件器和可清理的账号内验收任务验证写入与重复确认，不发送 QQ 测试消息。
- 记录实际启用状态、验证证据与未覆盖边界。

## Capabilities

### New Capabilities

- `owner-todo-activation`: 指定账号的 Todo 接入、凭据边界、写入验收与恢复。

### Modified Capabilities

无。

## Impact

现有 Java TimePrism 写入器/鉴权控制器、AstrBot owner 配置及精确私聊操作者绑定、服务器外部凭据和容器启动配置。必要时部署复现问题的修复；不改变账号权限、不重置密码、不修改网站源码、不向真实 QQ 用户或群发送测试消息。
