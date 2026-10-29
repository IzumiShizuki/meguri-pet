# 本机功能验收：2026-10-29

运行代码 `b4b03a40b12379c8447510c84083491a3ecdfa08`，分支 `fix/dialogue-reliability-20261028`。
本机 Core PID 67344，启动 16:43:30.597 UTC，部署核验 16:44:00.337 UTC；
正规启动器、编译产物、回环 18080 监听、健康/就绪以及 14 个最终真实终态的源码指纹已核验。
本轮只有本地提交，没有推送、合并或远端部署。文档后续提交不改变已运行代码。

## 已完成

- 人格 `speechStyle` 和 LORE `canon_examples` 用途接线；虚构内存 provider 覆盖同用户跨会话、其他身份、同意开关和故障恢复，捕获最终模型请求。
- Skills 选择/披露一致性、MCP structuredContent/schema、可空 operationId 和脱敏结果返回模型。
- 魔搭公开 discover/inspect 启用；普通聊天明确读请求进入已有授权的 AGENT 工具路径。冻结权限、网络限制、明确离线和引用非执行对照保留；没有导入、激活或安装未知包。
- 对齐站点 05f272e9 的周期 Todo 合同：星期多选、Mon–Fri、时区、提前提醒、日期实例完成、持久去重及不确定写入恢复。修复真实 Vue Proxy 经 IPC 保存失败。
- 真实验收依次发现并修复 PostgreSQL 幂等锁反转、取消请求租约竞争、超时终态租约竞争、双路取消发布竞争。SSE 使用会话范围轻量状态读取，保留顺序、身份与持久化先于广播。
- 既有 Harness 0.1.5-rc.1 恢复，原 Meguri bridge ready，可读此前虚构测试 completed 回执；未新建任务。

## 实际验证

| 层级 | 结果 |
| --- | --- |
| Java `mvn -B test` | 738 总数，733 通过、5 条件跳过、0 failure/error |
| Java `mvn -B -DskipTests package` | 成功 |
| Python `unittest discover -v` | 384 通过 |
| `pnpm test:ts` | 52 通过 |
| adapter 合同 | 21 TypeScript + 38 Python 通过，分开执行 |
| desktop Vitest | 68 文件、524 项通过；typecheck/build 通过 |
| 隐藏真实 renderer + sandbox preload + mock IPC | 周期 Todo 7/7，真实站点请求 0 |
| 最终协议矩阵 + 旧会话恢复 | 10 输入，9 完成/1 取消；75 协议检查通过；模型 6 通过/1 格式失败；provider 失败 0 |
| 最终普通聊天入口 | 4/4 完成、44 检查通过；真实公开工具审计成功，离线/引用对照零调用 |
| 前阶段普通内容包（6a9a911） | 20 完成、121 机械/隔离检查通过；18 语义通过、2 部分 |

最终取消终态观测 0.680 秒；普通发现整轮 24.290 秒、直接详情 9.517 秒。
这些是对应工作负载的单次观测，不是总体性能提升比例。原 90 秒失败证据保留。
五个 Java 跳过项需真实索引、独立恢复 DSN、真实控制或未知扩展导入，没有为通过测试扩大权限。

## 未验收与不足

- **MCP 生产接入 pending**：源计数 0、管理开关关闭。父会话等待用户批准仓库外本机管理凭据、仅回环认证管理 API，以及明确服务 URL/已有配置路径。没有凭据创建或管理权限变更。
- 外部 Skills catalog 为空，未找到可信 bundled 外部包；内置回调成功不代表外部包执行成功。
- 当前用户桌面未重启加载新 renderer。真实账号 UI 保存、生产双端同步、Harness 设置保存/任务卡显示未测；API/隔离 UI 结果不替代这些验收。
- Todo 自然语言周期创建明确引导编辑器；网页卡片约 30 秒检查、无闭页推送/声音，桌面需运行。两端各自去重，没有跨设备共享投递回执。
- RFC 短英文搜索无可用结果；长包装原作问题漏召回。已有诊断及有界改进设计，没有降低安全过滤或硬编码答案。
- 旧会话要求“只回复标记”时得到正确新标记及额外说明，严格格式失败；没有旧回答替换新回答。
- 真实长期记忆未写入/验证。`MockRagProvider` 是承载 174 条真实原作语料的兼容类名；正式记忆关闭不表示人格/原作召回关闭。A019 实际 1 hit、164 retrieval tokens；A020 漏召回。

## 证据和回退

完整报告及原始隔离证据留在 `reports/companion-acceptance-20261029/`：
`Meguri-local-acceptance-2026-10-29.md`、`deployment-status.json`、`protocol-b4-final-summary.json`、
`natural-entry-final-summary.json`、`natural-entry-final-audit.json`、各构建测试日志和 Todo 截图。
参考 [能力验收](capability-acceptance-2026-10-29.md) 与 [Todo 验收](todo-recurring-acceptance-2026-10-29.md)。

回退制品在 `tmp/local-deploy-rollback-f2e8dd7`，配套 `start-rollback-core.ps1` 位于报告目录。
它是已知源码重建版本。先重新核对当前进程、监听归属和活跃请求，再仅停止本项目 Core，
端口释放后通过正常 UAC 启动回退入口；不启动第二实例，不回滚或删除用户数据。无 schema 变更。
公开发现可在正规启动前设置 `MEGURI_MODELSCOPE_SKILLS_ENABLED=false` 撤销；imports 始终 false。

原有用户时间戳界面改动、未跟踪组件/OpenSpec 和五个排除文档保持原样。
