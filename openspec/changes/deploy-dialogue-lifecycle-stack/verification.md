# Verification: deploy-dialogue-lifecycle-stack

核验日期：2026-10-19，Asia/Shanghai。读取 proposal、design、delta spec、tasks 和实际部署/测试证据后核验。完整报告：`reports/dialogue-deployment-2026-10-19.md`。

| 维度 | 结果 |
|---|---|
| 完整性 | 7/7 任务完成，3 项需求有实现及验收证据 |
| 正确性 | 正常激活、私群聊、新会话、旧回调、搜索正文失败与挂起路径通过；线上故障注入未执行 |
| 一致性 | 符合候选后切换、loopback 端口、既有秘密/存储边界、本地收件器和保留回滚资产的设计 |

## 需求与场景映射

1. **Activated services expose the intended capabilities**
   - 远程与桌面激活：实际端口健康启用 Bing 与独立输入检查，容器 JAR 实测哈希等于最终 release。
   - `ops/scripts/redeploy-meguri-airi.ps1:197` 和 `test-dialogue-deployment-config.ps1:17` 防止配置继承回退。
   - 真实 Core 与插件客户端 HTTP/SSE 的模拟私聊、群聊完成；最终正式服务器和本机均 4 场景/5 检查，零真实 QQ 发送。
   - 生产构建界面经过真实网关/Core；用户确认原 Electron 窗口启动及刷新。测试主机桥替身的范围在报告明确。
2. **Failed activation is recoverable**
   - 候选先验收再切换；失败恢复分支保留旧容器身份。两个旧版本与原配置/插件备份实际存在，无删除数据卷。
   - `ChatDock.vue:523` 捕获会话，异步准备/发送返回先校验；实际浏览器旧草稿红测后绿测，并验证同会话取消仍能重试。
   - 完成旧发送与新会话的交叉后，新消息成功；刷新恢复已有消息，再次发送成功。
   - 候选失败场景通过代码审阅和回滚资产检查覆盖，未把它标为一次已执行的故障切换演练。
3. **Search results survive unavailable page bodies**
   - `WebRetrievalProvider.java:120` 保留 lane 期限余量、每页限时、只保留符合 URL/时效条件的摘要；正文有可用结果时优先使用正文。
   - `WebRetrievalProviderTest.java:174` 的错误/空完成参数回归、`:197` 的正文挂起与另一页可读回归先红后绿。
   - `turn-progress.ts:34` 和对应行为测试提供摘要来源的固定公开提示；正文和搜索摘要不混称。
   - 最终 web lane 强断言结果数大于零：服务器 3，本机 1；仍保留部分网页失败诊断。

## 验证证据

- Java package：640 项，635 通过、5 跳过、0 失败/错误。
- 桌面：431/431；typecheck 和生产 build 通过。
- PowerShell 7/5.1 配置回归与独立 PostgreSQL 配置回归通过。
- `.codex-runtime/deployment-20261018/` 保存最终健康、哈希、模拟验收、红绿日志和截图；详见报告的证据清单。
- `openspec validate deploy-dialogue-lifecycle-stack --strict` 通过；本轮相关已跟踪代码 `git diff --check` 无空白错误，只有既有 Windows 换行提示。

## 问题与结论

- CRITICAL：0。
- WARNING：未在线上注入候选失败。后续若需验证该故障切换场景，应在隔离容器/端口进行，预期检查旧 active 保持可用、候选失败不改 endpoint、回滚目标保留。当前报告已清楚限定覆盖范围。
- 已知运行限制：部分网站正文无法读取；正式 Todo 写入未配置；没有真实 QQ 群/用户送达验收。它们与设计中的授权边界一致，不伪报成功。

当前部署与修复已完成，保留变更供审阅，不执行归档。
