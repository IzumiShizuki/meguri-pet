# AstrBot 人格路由部署

日期：2026-10-21，Asia/Shanghai。用户明确授权部署后，已在个人服务器 `111.228.35.186` 的原有 AstrBot 4.28.1 中激活 Meguri 网关 **0.8.0**。

## 当前行为

```json
{
  "persona_routing_enabled": true,
  "meguri_persona_ids": ["default"]
}
```

只读核对时，服务器尚无自定义人格，当前 agent runner 的默认人格 ID 是 `default`，既有一条会话也没有单独指定人格。因此把 `default` 作为 Meguri 人格，保留当前聊天行为；今后创建或选择其他人格时，其普通聊天使用 AstrBot 自己的模型和上下文。`/meguri ...` 仍可显式使用 Core，原有权限和插件命令优先级继续生效。

后续在 **Meguri 网关插件配置 → meguri_persona_ids** 中调整实际人格 ID 名单即可。人格 ID 精确匹配、区分大小写。关闭 `persona_routing_enabled` 会恢复此前的全消息接管行为；开启但清空名单则让所有普通聊天使用 AstrBot。

## 部署范围

- 仅替换 `main.py`、`metadata.yaml`、`_conf_schema.json`，新增 `persona_routing.py`。
- 入口与人格策略取自上轮固定核验包，SHA256 为 `6fd604fe2ba18e6c84d520656d801aa190f6f7c879d2f96185b291090ccde48c`。
- Schema 基于线上原件合并两项人格设置，保留线上已有的隐藏字段等面板定制。
- 配置只增加上述两项。原有 Todo 创建、owner/操作者、QQ 目标、提醒关闭状态、日报及其他字段逐项保持一致。
- 原有 AstrBot 容器完成停止/启动以加载插件，最终启动时间为北京时间 **01:22:44**；日志在 **01:23:01** 确认网关初始化成功，并记录 0.8.0。
- Core 容器的 ID、镜像、启动时间未变，健康仍为 `ok`。两个容器的镜像、身份、网络、端口与挂载内容均保持原值；其他原有插件文件逐个核对哈希未变。

## 验证与部署中发现的问题

| 检查 | 结果 |
| --- | --- |
| 本地人格路由测试 | 25/25 通过 |
| 服务器候选隔离验收 | 12/12 通过 |
| 部署后隔离验收 | 12/12 通过 |
| 备份与恢复演练 | 原有 29 个插件文件和配置的恢复哈希全部一致 |
| 最终文件及配置 | 4 个部署文件等于候选哈希；原有配置值、面板定制与其他文件保留 |
| 服务与边界 | AstrBot 0.8.0 已加载，Core 健康且未重启，真实平台测试发送 0 次 |

隔离验收覆盖默认人格、非名单人格、切回默认、显式 Core 命令、会话强制人格、两个群聊、发送者/群权限、解析接口不可用、插件命令优先和默认人格消息不进入 Meguri 待办草稿。验收使用服务器安装的原生人格解析方法源码、合成 SDK 事件和 gateway spy；没有创建真实 Core 对话或发送 QQ 测试消息，也不等同于真人收件验证。

准备阶段的首次候选从可变工作区读入了另一项未提交的配置辅助模块，服务器隔离检查发现缺少依赖，尚未改动线上插件即被拦住。随后改用已核验固定包重建最小更新，未部署该无关改动。

首轮激活后的校验把 Docker 挂载数组按顺序比较，因 Docker 返回顺序变化触发自动回滚。旧插件及原配置已实际恢复，AstrBot 正常运行。核对确认挂载内容完全相同后，校验改为按挂载目标排序比较；重新部署同一核验候选后所有检查通过。备份、首次拒绝的候选和失败诊断均保留，没有隐去尝试记录。

## 备份与回滚

受限服务器目录：`/opt/meguri/staging/releases/persona-routing-20261021`，包含 `original-plugin/`、`original-config.json`、`restore-rehearsal/`、`manifest.json`、`state.json`、候选与部署脚本。目录权限 0700，配置备份 0600；不在报告展示凭据、token 或私聊身份。

确需回滚时，在该个人服务器运行：

```sh
python3 /opt/meguri/staging/releases/persona-routing-20261021/remote_deploy.py rollback
```

该脚本停止原 AstrBot，保留当前四个文件和配置，恢复原有 0.7.0 文件与配置，仅移除原先不存在的 `persona_routing.py`，随后启动同一容器。无需回滚 Core、数据库或网络；保留全部回滚资产。

本机受限证据：`.codex-runtime/persona-routing-20261021/` 下的 preflight、manifest、stage、activate、verify、挂载核对与诊断 JSON，以及操作脚本。未推送 Git，未归档 OpenSpec。
