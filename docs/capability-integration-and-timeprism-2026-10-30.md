# 本地改进整合与组合回归

2026-10-30 07:35 UTC。分支 `fix/dialogue-reliability-20261028`。用户批准六项改进并行执行后，本轮完成 TimePrism 桌面修复，并整合已复审的 MCP 元数据兼容与安全网页正文读取。没有 push、合并主分支、部署、重新发起 UAC 或操作生产 Todo。

## 本地提交

| 来源 | 本地提交 | 结果 |
|---|---|---|
| 本轮 TimePrism 修复 | `daaebec7282537af5ba4acd93af2f64b59263c97` | 快照查询不派发提醒、隔离旧读取、暂停规则保护、完整日期边界输入与验证 |
| `d136576` | `7bfb90a` | 兼容合法 MCP execution.taskSupport；拒绝 required、未知值/字段，原审批和写风险保留 |
| `db75cd5` | `18be874` | 默认关闭的安全 web_fetch；逐跳公网地址检查、固定解析地址、TLS主机校验、限制超时/大小/类型/重定向 |
| `a2db597` | `8a653eb6f2ffce1f46765d519149469c4aaff657` | source/link/body独立预算，避免长链接挤掉正文；外部内容保持不可信 |

两个本地 bundle 及前置链路核验后导入，三笔 cherry-pick 无冲突。没有启用运行时开关、管理凭据、未知扩展或新增服务。其他独立任务的 Skills/MCP 可用性状态和 B站候选尚未在本轮收到或整合。

## 验证

| 验证 | 实际结果 |
|---|---|
| TimePrism新增10项 | 修复前0通过/10失败，修复后全部通过 |
| 桌面针对性/全量 | 57/57；534/534，0失败0跳过 |
| Typecheck / desktop build | 通过；保留既有大chunk告警 |
| MCP/web_fetch/检索回答针对性 | 141/141，0失败/错误/跳过 |
| Java整合全量 | 988项：983通过、0失败、0错误、5跳过 |
| 与上一主树Java归档比较 | 887个共有测试状态不变，101个新增全过，0删除 |
| 源码与资源 | 测试副本811个源码/契约文件与主树一致，404个既有测试资源复用 |
| 用户已有工作 | 15个原有修改/未跟踪文件SHA256不变；均未纳入本轮提交 |
| 两个OpenSpec strict / diff check | 通过 |

5个跳过项仍为 Everything 实机索引、两项 PostgreSQL 恢复、Shizuki 实机窗口和需显式开启的 ModelScope 下载/导入。没有为消除跳过而启用它们。141项是988项的子集，不重复相加。

Java使用独立源码副本、独立user.home、离线Maven缓存、清理真实provider环境变量与已验证的JDK测试TCP回退参数。只运行假模型/受控测试服务；没有真实模型调用或线上MCP请求。桌面完整测试完成后只有Java候选和文档变化，因此没有重复刷桌面测试。

复现命令（仓库根）：

```powershell
.\reports\capability-integration-20261030\run-isolated-tests.ps1 -Tests 'McpExecutionMetadataCompatibilityTest,PublicWebFetchCapabilityTest,PublicWebFetchPolicyTest,PublicWebFetcherTest,PinnedWebFetchTransportTest,PublicWebFetchProjectionTest,HttpMcpAdapterTest,McpEndpointPolicyTest,McpSourceManagerTest,CapabilityRuntimeReactActionExecutorTest,TurnMcpContentIntegrationTest,IntegratedRetrievalReplyTest,ReplyRepairLifecycleTest' -LogName targeted.log
.\reports\capability-integration-20261030\run-isolated-tests.ps1 -LogName full.log
```

证据：`reports/capability-integration-20261030/` 下的 Surefire ZIP、日志、source-manifest.json、resource-hashes.json、verification-summary.json。TimePrism证据仍在 `reports/timeprism-parity-20261030/`。原始记录只留本地。

## 尚未完成的验收

- 本轮进程核对旧Core PID67344仍监听127.0.0.1:18080，启动时间2026-10-29T16:43:30.597Z未变；沿用已验证版本b4b03a4记录，源码HEAD不能代表运行版本。未重启Core或桌面。
- 前次Windows UAC操作取消后未重试，具体重发待批准；下次部署须重新生成HEAD/制品守卫，旧1d5b00dc计划不能直接运行。
- 安全web_fetch默认关闭。既有公开RFC探测解析到198.18.0.82，连接前被拒绝，HTTP0；实际公网TLS/重定向/DDG仍未通过，不放宽地址拦截。
- 新桌面字段、真实模型格式/口吻/召回、生产账号双端Todo尚未验收。桌面查询副作用隔离不等于站点GET纯只读：站点仍会生成实例，具体后续设计见TimePrism改进文档。
- AstrBot周期草稿、站点纯查询接口、一次性时区兼容及编辑PUT冲突恢复仍为明确后续设计；没有修改只读站点仓库。正式记忆与训练未开启。

这些未测项不计入通过统计，也没有创建需要清理的生产待办数据。
