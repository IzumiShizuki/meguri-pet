## Why

Meguri 已经能陪用户聊天、检索、执行受限 ReAct 能力和打开本地文件，但无法把一件**真正的工程任务**交给用户本机已经安装并登录的工作 Agent。用户现在必须在 Codex / DeepSeek Harness 里重新描述一遍需求，桌宠与执行器之间没有可信、可审计的转交边界。

参考实现 AAAAGENT 把这条链路做成"任务卡 → 用户确认 → 只投递一次 → 只读回执"，本变更把同等能力引入 Meguri 的原生桌面端，并保持 Meguri 现有的权威模型：桌宠不是执行者，执行权留在本地执行器。

## What Changes

- 新增桌面主进程本地 Agent 运行时：发现本机 Codex CLI 与 DeepSeek Harness CLI，报告安装/登录/预设状态，不安装、不升级、不代登录、不读取模型凭据。
- 新增 Codex 通道：官方 `codex app-server --stdio` JSONL RPC（`thread/list` / `thread/read` / `thread/resume` / `turn/start` / `account/read`），只列出用户自己发起、位于本机绝对路径下的任务。
- 新增 DeepSeek Harness 通道：从 `web.log` 读取回环启动地址换取浏览器 cookie，走 `/api/<method>` RPC（`session/list` / `session/create` / `session/rename` / `session/prompt` / `session/page`）。
- 新增 Harness 工作预设 `meguri-work-v1`：只在启用时写入 `<dshHome>/.agent-presets/`，字节级契约，**绝不覆盖**已存在的用户文件。
- 新增持久化回执存储 `local-agent-receipts.sqlite`：任务卡正文、目标、阶段、回执与 `dispatchAttempted` 去重标记，用 `BEGIN IMMEDIATE` 串行化读改写。
- 新增转交状态机 `awaiting_confirmation → forwarding → accepted → completed | unknown | unavailable`，先落盘 claim 再调用执行器，保证崩溃、重启或重复确认都不会产生第二次发送。
- 新增 Harness 回执折叠：严格连续序号、唯一 turn 绑定、审批平衡、`turn/end.reason.kind` 判定；任何不满足都判 `unknown`。
- 新增窄 IPC 与 renderer 任务卡 UI：`prepare`（冻结任务卡）、`confirm(id, version)`（确认确切版本）、`refresh`（只读回执）、`dismiss`、`openNative`；**不暴露任何通用发送入口**。
- 新增设置页「工作 Agent」入口：启用开关、可选由 Meguri 启动 Harness 网页宿主、可选 Harness 数据目录、连接测试。

## Capabilities

### New Capabilities

- `desktop-local-work-agent-forwarding`: 用户可以把一件已确认的任务转交给本机 Codex 或 DeepSeek Harness，且转交至多发生一次、随时可查回执、绝不由桌宠代为审批。
- `desktop-local-work-agent-settings`: 用户可以在桌面设置中查看本地执行器的安装/登录状态、显式启用转交、可选让 Meguri 启动 Harness 网页宿主，并测试连接。

### Modified Capabilities

- None.

## Impact

- 新增 `apps/desktop-meguri/src/main/local-agents/`（paths、receipts、codex-bridge、dsh-bridge、dsh-preset、dsh-discovery、work-forwarding）与 `local-agent-runtime.mjs`、`local-agent-config.mjs`。
- 修改 `apps/desktop-meguri/src/main/index.mjs`（IPC、观察循环、退出清理）、`src/preload/index.cjs`、`src/shared/channels.cjs`、`src/shared/desktop-contract.ts`。
- 新增 renderer `stores/work.ts`、`components/WorkPanel.vue`，并接入 `StageView.vue` / `AmbientStage.vue` / `ControlsIsland.vue`（`Ctrl+Shift+J`）。
- 扩展 `SettingsView.vue` 与 `styles.css`；扩展 `scripts/preload-smoke.mjs` 与 `preview-bridge.ts`。
- 新增 `docs/local-work-agents.md`。
- 不把任何 API Key、token、cookie 写入仓库、桌面偏好 JSON、renderer localStorage 或日志；桌宠不改变执行器的沙箱、批准与模型设置。
- 不接触 Python Core、Java Core、正式 memory、Persona 或 Adapter 协议；本能力完全位于桌面端边界内。
