# 把任务转交给本机 Codex / DeepSeek Harness

Meguri 桌宠可以把你描述的一件事整理成**任务卡**，在你确认后转交给本机已经安装并登录的 **Codex** 或 **DeepSeek Harness**。桌宠不安装、不升级、不代登录这两个执行器，也从不读取它们的模型凭据。

## 边界

```text
Renderer（任务卡 + 确认）
      |
      | 窄 IPC（冻结一张卡 / 确认某个版本 / 只读回执）
      v
Electron 主进程  local-agent-runtime.mjs
      |                         |
      | codex app-server --stdio|  loopback HTTP + token
      v                         v
  本机 Codex              本机 DeepSeek Harness (dsh)
   （自己登录、自己沙箱、自己审批）
```

- **执行权在本地执行器**。桌宠只递交一次用户已确认的正文；模型、沙箱、批准策略、持久化都归执行器所有。
- **没有通用发送入口**。renderer 只能 `prepare`（冻结任务卡）、`confirm(id, version)`（确认某个确切版本）和 `refresh`（只读回执）。传输层不暴露任何 `send`。
- **一次性投递**。`confirm` 先在同一事务里把 `phase` 写成 `forwarding` 并落盘，之后才调用执行器。崩溃、重启或重复点击都不会产生第二次发送。
- **未知就是未知**。响应丢失时保留为“结果未知”，不会自动重发；需要人工核对原任务。
- **不审批**。Codex 的命令/文件审批请求一律被拒绝；Harness 的待批准状态只作为回执展示，请在原生界面处理。

## 使用

1. 打开 **设置 → 工作 Agent**，勾选「启用任务转交」。这一步会把 Meguri 的工作预设安装到 Harness 数据目录（`<dshHome>/.agent-presets/meguri-work-v1/`），不覆盖任何已有文件。
2. 需要 Meguri 顺带把 Harness 网页宿主拉起来时，再勾选「由 Meguri 启动 Harness 网页宿主」。未勾选时请自己运行 `dsh web`。
3. 点击「测试连接」确认 Codex / Harness 的实际状态。
4. 在舞台上按 `Ctrl + Shift + J`，或点控制岛的「转交」。
5. 选择执行者与目标，写下要做的事，点「整理任务卡」。
6. 核对任务卡正文与目标，点「确认并转交」。**这是唯一会真正发出请求的动作。**

## 执行器细节

### Codex

- 走官方 `codex app-server --stdio` JSONL RPC：`initialize` / `initialized` / `account/read` / `thread/list` / `thread/read` / `thread/resume` / `turn/start`。
- 只列出用户自己发起的任务（`source` 为 `cli`/`vscode`、非临时、非子 agent、工作目录是本机绝对路径）。同一线程的分叉记录只出现一次。
- `turn/start` 带 `clientUserMessageId`，与持久化的 `requestId` 一致，便于事后核对。
- 可执行文件查找顺序：`PET_CODEX_EXECUTABLE` → `%LOCALAPPDATA%\OpenAI\Codex\bin\<版本>\codex.exe`（取最新）→ `PATH`。
- `account/read` 在 Codex 刷新账号元数据时可能失败；此时回退到本地线程索引，仍能把状态判为可用，但不改任何权限。

### DeepSeek Harness

- 只连回环地址：从 `<harnessServiceHome>\web.log` 首部读 `dsh web: http://127.0.0.1:<port>/?token=...`，再用该 URL 换一次浏览器 cookie，然后对 `/api/<method>` 发 `{type:'client-request', rpcId, method, payload:{args}}`。启动地址本身就是凭据，不跟随重定向、不外发。
- 用到的方法：`session/list`、`session/create`、`session/rename`、`session/prompt`、`session/page`。
- 回执从 Harness 自己的事件序列折叠得出：`turn/start` → `user/message.source.rpcId` 绑定请求 → `approval/asked|decided` 平衡 → `assistant/message` 取文本 → `turn/end.reason.kind`。序号必须严格连续、turn 必须唯一绑定、审批必须平衡，否则判 `unknown`。
- 工作会话使用固定预设 `meguri-work-v1`，固定标题避免宿主的额外起标题调用。

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `PET_CODEX_EXECUTABLE` | 空 | 指定 Codex CLI 绝对路径 |
| `CODEX_HOME` | `%USERPROFILE%\.codex` | Codex 数据目录 |
| `PET_DSH_CLI` | 空 | 指定 Harness CLI（`@deepseek-ai/dsh` 的 `lib/bin.js`）绝对路径 |
| `DSH_HOME` | `%USERPROFILE%\.dsh` | Harness 数据目录（工作预设装在这里） |
| `PET_HARNESS_HOME` | `%APPDATA%\Meguri\harness-service` | 服务目录，含 `web.log` 与工作区 |
| `MEGURI_LOCAL_AGENT_CONFIG_FILE` | `%APPDATA%\Meguri\local-agents.json` | 桌宠自己的开关文件 |
| `MEGURI_LOCAL_AGENT_RECEIPT_FILE` | `%APPDATA%\Meguri\local-agent-receipts.sqlite` | 回执数据库 |

设置页也可以覆盖 Harness 数据目录。

## 数据

- `local-agents.json`：只有两个开关和一个可选目录，没有任何凭据。
- `local-agent-receipts.sqlite`：任务卡正文、目标、阶段、回执结果与 `dispatchAttempted` 去重标记。删除它会丢失“这张卡已经发过”的记忆，因此不要在有待观察任务时删除。

## 离线与故障处理

- **「任务转交尚未启用」**：设置里没有勾选启用。
- **「没有找到本机 Codex」**：Codex 未安装或未设置 `PET_CODEX_EXECUTABLE`。
- **「本机 Codex 当前不可用」**：app-server 无法启动，或 `account/read` 与线程索引都失败。
- **「DeepSeek Harness 尚未启动」**：没有正在运行的 `dsh web`；勾选自动启动或自己启动。
- **「DeepSeek Harness 需要重新登录」**：Harness 的浏览器会话失效，请在 Harness 网页重新登录。
- **「这张任务卡已经变化」**：任务在你看过之后被更新过；刷新后重新确认。
- **「结果未知」**：请求可能已经送达。请到 Codex / Harness 中核对，不要指望桌宠重发。
- **「等待原生批准」**：请在原生执行器中处理审批；桌宠没有审批界面。

## 验证

```powershell
D:\environment\nodejs\global\pnpm.cmd --filter '@meguri/desktop-meguri' test
D:\environment\nodejs\global\pnpm.cmd --filter '@meguri/desktop-meguri' typecheck
```

单元测试覆盖回执持久化与去重、`confirm` 的版本闸门、未知投递不重发、Harness 事件折叠的全部拒绝分支，以及工作预设“绝不覆盖用户文件”。
