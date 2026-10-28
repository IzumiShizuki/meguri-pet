# AI 对话全链路验证与配置

输入小模型与 Agent 规划模型是两个入口。普通文字对话的 Java Core 链路为：原始请求与会话身份校验 → 有界小模型检查/改写 → 原文决定检索/权限/操作意图 → RAG、记忆与上下文组装 → 可选 Agent 规划 → 主模型生成 → HTTP/SSE → 完成话轮与原始上下文持久化。直接天气等确定性能力继续按原文执行，不经过改写。

## 输入检查

`InputModelChecker` 只产生辅助措辞，不替换 `TurnRequest.message`。主模型收到原文和标记为非权威的改写；会话历史、主题、联网开关、工具权限和写操作授权始终来自原文。含提醒、Todo、操作、拒绝或联网限制的输入保守直通，不交给改写模型。数字、时间、URL、英文标识等变化会拒绝改写。该检查不是完整的语义等价证明；主流程仍必须独立校验权限和操作。

失败、空响应、格式错误、超时、并发槽已满均回退原文。整体期限覆盖排队、响应头、完整正文与解码，防止上游只发响应头后停住。取消会中断该次请求并释放槽；迟到响应没有共享的可变结果槽，不能覆盖下一轮。改写是可丢弃的提示辅助信息，超出主模型提示预算时整块移除。

| 环境变量 | 用途/默认值 |
|---|---|
| `MEGURI_INPUT_CHECK_MODEL` | 独立输入模型；未配置时 Core 默认关闭此步骤 |
| `MEGURI_INPUT_CHECK_ENABLED` | `true`，显式 `false` 可关闭 |
| `MEGURI_INPUT_CHECK_BASE_URL` | 默认主模型地址；支持 HTTPS 或本机 HTTP |
| `MEGURI_INPUT_CHECK_API_KEY_FILE` | 绝对路径密钥文件；默认复用主模型密钥文件 |
| `MEGURI_INPUT_CHECK_TIMEOUT_MS` | 750，支持 1–5000；同时受话轮剩余期限约束 |
| `MEGURI_INPUT_CHECK_QUEUE_TIMEOUT_MS` | 50，支持 0–500 |
| `MEGURI_INPUT_CHECK_MAX_CONCURRENCY` | 2，支持 1–32，与主模型/规划模型独立 |

密钥不得以内联变量 `MEGURI_INPUT_CHECK_API_KEY` 提供。改变模型/地址配置需要重启 Core；没有实现运行中热切换。`/health.input_check` 只显示 `state` 与模型名，不返回密钥。

本地 `start-meguri-core.ps1` 在已有 DeepSeek 密钥时为输入角色默认选择现有 `deepseek-v4-flash`，可用以上变量选择其他兼容小模型。一次真实合成输入请求耗时 1356ms，因此桌面启动器默认给输入角色 2000ms；通用 Core 的默认预算仍为 750ms。单次样本不代表稳定延迟或分位数性能。这个默认复用现有可用端点，没有下载或训练新的小模型。容器通过 Compose 传递输入模型配置；默认模型留空，需要显式配置。如果使用不同供应商，应另挂载并指定对应密钥文件。

## 会话与 RAG

- 桌面新会话同步清空 sending、active turn、summary busy/controller，旧事件、快照、收尾和摘要结果以 session/controller 身份隔离。
- 应用重新打开保留已完成消息；中断请求变为可恢复状态，能够继续发送。Core 的 PostgreSQL 进程恢复测试另验证上下文、顺序事件、幂等身份与跨会话隔离。
- RAG/记忆 HTTP 响应必须有合法的 `items` 数组。空数组是成功空结果；畸形响应、503、超时是不可用。Lore 可继续用本地词法匹配，降级标识传入 typed retrieval trace 并在桌面显示固定公开提示。
- 外部 Agent 的回调接口与 RAG HTTP 返回不是同一个机制。现有 Agent 测试覆盖 schema/source 身份、重复/迟到回调；本轮补充的是 RAG 返回和下一轮的隔离。

## 运行验证

先激活 Java 21，并将 `JAVA_TOOL_OPTIONS` 的 `jdk.net.unixdomain.tmpdir` 指向仓库 `tmp/jdk-unix-sockets`，避免 Windows socket 路径过长。

```powershell
pnpm --filter @meguri/desktop-meguri test:unit
pnpm test:ts
& 'D:\environment\anaconda3\envs\py314\python.exe' -m pytest -ra
```

Core 的 `InputModelCheckerTest`、`InputModelCheckerFactoryTest`、`DialogueModelRouteHttpTest`、`PythonRagGatewayTest`、`PythonMemoryGatewayTest` 使用真实回环 HTTP 随机端口和确定性模拟上游。

```powershell
./ops/scripts/check-runtime-postgres-recovery.ps1 -DatabaseSecretFile <现有测试连接文件的绝对路径>
```

重启测试在专有随机 schema 内启动与结束自己的 Core JVM，通过鉴权 HTTP/SSE 验证三次启动、恢复后继续发送及独立新会话，finally 清理本次 schema。它不重启正在使用的 Core，不写正式用户记忆。

测试结果与生产启用情况分别记录在 `reports/ai-dialogue-lifecycle-audit-2026-10-18.md`。AstrBot 模拟发送见 `docs/astrbot-conversation-simulation.md`；消息始终落在本地 collector，不等于 QQ 实际送达。
